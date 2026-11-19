package us.poliscore.funding;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.bill.*;
import us.poliscore.service.storage.S3PersistenceService;
import us.poliscore.service.storage.repository.BillRepository;

class BillFundingServiceTest {
    private static final String ID = "BIL/us/congress/119/hr/5371";
    private final BillFundingService service = new BillFundingService();
    private final BillText source = BillText.factory(ID, 123, "This Act contains no priced appropriations.",
            LocalDate.of(2025, 11, 12), "ENR", BillTextFormat.TEXT);
    private final Map<String, byte[]> objects = new HashMap<>();
    private int reads;
    private boolean writeFails;
    private int pgWrites;

    BillFundingServiceTest() throws Exception {
        objects.put(source.getId() + ".json", PoliscoreUtil.getObjectMapper().writeValueAsBytes(source));
        service.archive = new S3PersistenceService() {
            @Override public <T> Optional<ArchiveJson<T>> readJsonWithEtag(String key, Class<T> type, Duration timeout) {
                reads++;
                return Optional.ofNullable(objects.get(key)).map(bytes -> {
                    try { return new ArchiveJson<>(PoliscoreUtil.getObjectMapper().readValue(bytes, type), "etag"); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                });
            }
            @Override public void writeJsonIfUnchanged(String key, byte[] bytes, String etag, Duration timeout) {
                if (writeFails) throw new IllegalStateException("archive unavailable");
                objects.put(key, bytes);
            }
        };
        service.bills = new BillRepository() {
            @Override public boolean saveFunding(String id, BillText text, FundingSnapshot value, int timeout) {
                pgWrites++;
                assertEquals(ID, id);
                assertTrue(value.appliesTo(text));
                return true;
            }
        };
    }

    @AfterEach void shutdown() { service.stop(); }

    private Bill bill() {
        var bill = new Bill();
        bill.setId(ID);
        bill.setTexts(List.of(source));
        return bill;
    }

    @Test void builderEmptyResultSurvivesServerSwapAndWarmPageDoesNoIo() {
        var interp = new BillInterpretation();
        service.prepare(bill(), source, interp);
        assertTrue(interp.getFunding().rows().isEmpty());
        assertEquals(2, objects.size());
        var warm = bill();
        int afterBuild = reads;
        service.forPage(warm, null);
        assertEquals(afterBuild, reads);
        assertEquals(0, pgWrites);
        // Fresh serving database with only old bill metadata; recover from the durable snapshot.
        source.setFunding(null);
        var cold = bill();
        service.forPage(cold, null);
        assertEquals(afterBuild + 1, reads);
        assertEquals(1, pgWrites);
        assertTrue(cold.getTexts().first().getFunding().rows().isEmpty());
        service.forPage(cold, null);
        assertEquals(afterBuild + 1, reads);
    }

    @Test void persistenceFailureOmitsTheTableWithoutCachingFalseEmpty() {
        var bill = bill();
        writeFails = true;
        service.forPage(bill, null);
        assertNull(bill.getTexts().first().getFunding());
        assertEquals(1, objects.size());
        assertEquals(0, pgWrites);
        int failedReads = reads;
        service.forPage(bill, null);
        assertEquals(failedReads, reads);
    }

    @Test void concurrentVisitorSkipsAndTimeoutInterruptsTheWorker() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var extra = new AtomicInteger();
        try (var clients = Executors.newSingleThreadExecutor()) {
            var first = clients.submit(() -> service.guarded("same", BillFundingService.Budget.of(Duration.ofMillis(350)), () -> {
                started.countDown();
                try { new CountDownLatch(1).await(); return 1; }
                catch (InterruptedException e) { interrupted.countDown(); throw e; }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertNull(service.guarded("same", BillFundingService.Budget.of(Duration.ofSeconds(8)), extra::incrementAndGet));
            assertNull(first.get(2, TimeUnit.SECONDS));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(0, extra.get());
        }
    }

    @Test void pageReturnsWithinEightSecondBudgetWhenArchiveStalls() throws Exception {
        var cancelled = new CountDownLatch(1);
        service.archive = new S3PersistenceService() {
            @Override public <T> Optional<ArchiveJson<T>> readJsonWithEtag(String key, Class<T> type, Duration timeout) {
                assertTrue(timeout.compareTo(Duration.ofSeconds(8)) <= 0);
                try { new CountDownLatch(1).await(30, TimeUnit.SECONDS); }
                catch (InterruptedException e) { cancelled.countDown(); throw new CancellationException(); }
                throw new AssertionError("Archive read exceeded deadline");
            }
        };
        var bill = bill();
        assertTimeoutPreemptively(Duration.ofMillis(8500), () -> service.forPage(bill, null));
        assertNull(bill.getTexts().first().getFunding());
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertEquals(0, pgWrites);
    }

    @Test void missingSourceIsRetryableAndNeverBecomesACompletedEmptyResult() {
        objects.clear();
        var bill = bill();
        service.forPage(bill, null);
        assertNull(bill.getTexts().first().getFunding());
        assertTrue(objects.isEmpty());
        assertEquals(0, pgWrites);
    }

    @Test void changedTextRevisionCannotReuseAnOldSnapshot() {
        var interp = new BillInterpretation();
        service.prepare(bill(), source, interp);
        String originalKey = BillFundingService.key(source);
        source.setLegiscanId(234);
        assertFalse(interp.getFunding().appliesTo(source));
        assertNotEquals(originalKey, BillFundingService.key(source));
    }
}
