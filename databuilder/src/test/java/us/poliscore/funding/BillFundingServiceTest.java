package us.poliscore.funding;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.bill.*;
import us.poliscore.service.storage.S3PersistenceService;
import us.poliscore.service.storage.repository.BillRepository;

class BillFundingServiceTest {
    private static final String ID = "BIL/us/congress/119/hr/5371";
    private final BillFundingService service = new BillFundingService();
    private final BillText source = BillText.factory(ID, 123, "Be it enacted by the Legislature. Section 1. This Act takes effect immediately.",
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
        bill.setInterpretations(List.of(interpretation(ID, source.getVersion())));
        return bill;
    }

    @Test void replacesAnOlderSnapshotWithActualPriorFundingAndBillLink() throws Exception {
        var current = BillText.factory(ID, 123, AppropriationsBillTest.document("hr5371enr.xml.gz"),
                LocalDate.of(2025, 11, 12), "ENR", BillTextFormat.CONGRESS_BILL_XML);
        current.setFunding(new FundingSnapshot(1, current.getVersion(), FundingSnapshot.revision(current),
                current.getLegiscanId(), current.getLastUpdate(), 1L, List.of(), List.of(), false));
        var bill = bill();
        bill.setIntroducedDate(LocalDate.of(2025, 9, 16));
        bill.setTexts(List.of(current.metadataOnly()));
        objects.put(current.getId() + ".json", PoliscoreUtil.getObjectMapper().writeValueAsBytes(current));

        var prior = new Bill();
        prior.setId("BIL/us/congress/119/hr/1968");
        var priorText = BillText.factory(prior.getId(), 456, AppropriationsBillTest.document("hr1968enr.xml.gz"),
                LocalDate.of(2025, 3, 15), "ENR", BillTextFormat.CONGRESS_BILL_XML);
        prior.setTexts(List.of(priorText.metadataOnly()));
        objects.put(priorText.getId() + ".json", PoliscoreUtil.getObjectMapper().writeValueAsBytes(priorText));
        service.bills = new BillRepository() {
            @Override public List<Bill> fundingCandidates(Bill bill, int timeout) {
                return List.of(prior);
            }
            @Override public boolean saveFunding(String id, BillText text, FundingSnapshot value, int timeout) {
                pgWrites++;
                assertTrue(value.appliesTo(text));
                return true;
            }
        };

        service.forPage(bill, null);
        var result = bill.getTexts().first().getFunding();
        assertNotNull(result);
        assertEquals(1, pgWrites);
        var medical = result.rows().stream().filter(row -> row.label().endsWith("/ MEDICAL SERVICES"))
                .findFirst().orElseThrow();
        assertEquals(75_039_000_000L, medical.prior());
        assertEquals(prior.getId(), medical.priorBill());
    }

    @Test void texasSecondYearUsesThePreviousColumnInTheSameBill() throws Exception {
        var bill = new Bill();
        bill.setId("BIL/us/tx/2160/sb/1");
        var text = BillText.factory(bill.getId(), 789, AppropriationsBillTest.document("89R.tx.txt"),
                LocalDate.of(2025, 6, 22), "ENROLLED-789", BillTextFormat.TEXT);
        var interpretation = new BillInterpretation();

        service.prepare(bill, text, interpretation);

        var government = interpretation.getFunding().rows().stream()
                .filter(row -> row.label().equals("General Government") && row.year() == 2027)
                .findFirst().orElseThrow();
        assertEquals(5_313_484_867L, government.current());
        assertEquals(6_152_967_349L, government.prior());
        assertNull(government.priorBill());
    }

    @Test void billPageUsesTheArchivedVersionOfTheSelectedInterpretation() throws Exception {
        String billId = "BIL/us/nm/2251/hb/2";
        String introducedVersion = "INTRODUCED-3325082";
        String enrolledVersion = "ENROLLED-3391626";
        var introduced = BillText.factory(billId, 3325082,
                AppropriationsBillTest.document("hb2-2026-introduced.nm.txt.gz"),
                LocalDate.of(2026, 1, 20), introducedVersion, BillTextFormat.TEXT);
        var enrolled = BillText.factory(billId, 3391626, AppropriationsBillTest.document("26.nm.txt"),
                LocalDate.of(2026, 3, 11), enrolledVersion, BillTextFormat.TEXT);
        for (var text : List.of(introduced, enrolled)) {
            objects.put(text.getId() + ".json", PoliscoreUtil.getObjectMapper().writeValueAsBytes(text));
        }
        var bill = new Bill();
        bill.setId(billId);
        bill.setTexts(List.of(introduced, enrolled));
        var introducedAnalysis = interpretation(billId, introducedVersion);
        bill.setInterpretations(List.of(introducedAnalysis));
        service.bills = new BillRepository() {
            @Override public boolean saveFunding(String id, BillText text, FundingSnapshot value, int timeout) {
                pgWrites++;
                assertEquals(billId, id);
                assertEquals(introducedVersion, text.getVersion());
                assertTrue(value.appliesTo(text));
                return true;
            }
        };

        service.forPage(bill, null);
        var funding = introducedAnalysis.getFunding();
        assertNotNull(funding);
        assertEquals(4, funding.extractorVersion());
        assertEquals(introducedVersion, funding.version());
        assertEquals(549_844_400L, funding.rows().stream().filter(row -> row.label().equals("JUDICIAL"))
                .findFirst().orElseThrow().current());
        assertNull(bill.getTexts().last().getFunding());
        assertEquals(1, pgWrites);
    }

    @Test void pageWithNoAnalysisDoesNotReadOrWriteFunding() {
        var bill = new Bill();
        bill.setId(ID);
        bill.setTexts(List.of(source));
        service.forPage(bill, null);
        assertEquals(0, reads);
        assertEquals(0, pgWrites);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "<html><p>The HTML and Word versions of this bill are not available. Please see the PDF for the content of this bill.</p></html>",
        "Be it enacted by the Legislature. Section 1. The Legislature allocates $900 using a format the parser does not support.",
        "Text is not legislation."
    })
    void unverifiedEmptyResultsAreNeverPersisted(String document) throws Exception {
        source.setText(document);
        objects.put(source.getId() + ".json", PoliscoreUtil.getObjectMapper().writeValueAsBytes(source));
        var bill = bill();
        service.forPage(bill, null);
        assertNull(bill.getTexts().first().getFunding());
        assertNull(bill.getInterpretation().getFunding());
        assertEquals(1, objects.size());
        assertEquals(0, pgWrites);
    }

    private static BillInterpretation interpretation(String billId, String version) {
        var interpretation = new BillInterpretation();
        interpretation.setId(BillInterpretation.generateId(billId, version, null));
        interpretation.setSourceBillTextVersion(version);
        return interpretation;
    }

    @Test void builderEmptyResultSurvivesServerSwapAndWarmPageDoesNoIo() {
        var interp = new BillInterpretation();
        service.prepare(bill(), source, interp);
        assertTrue(interp.getFunding().rows().isEmpty());
        assertTrue(interp.getFunding().verifiedEmpty());
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

    @Test void replacesAnUnverifiedEmptySnapshotAtTheSameArchiveKey() throws Exception {
        String key = BillFundingService.key(source, FundingSnapshot.EXTRACTOR_VERSION);
        var oldSnapshot = new FundingSnapshot(FundingSnapshot.EXTRACTOR_VERSION, source.getVersion(),
                FundingSnapshot.revision(source), source.getLegiscanId(), source.getLastUpdate(),
                1L, List.of(), List.of(), false);
        var mapper = PoliscoreUtil.getObjectMapper();
        ObjectNode oldFields = mapper.valueToTree(oldSnapshot);
        oldFields.remove("verifiedEmpty");
        objects.put(key, mapper.writeValueAsBytes(oldFields));

        var bill = bill();
        service.forPage(bill, null);

        assertTrue(bill.getInterpretation().getFunding().verifiedEmpty());
        assertTrue(mapper.readValue(objects.get(key), FundingSnapshot.class).verifiedEmpty());
        assertEquals(1, pgWrites);
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
        assertEquals(1, objects.size());
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
        String originalKey = BillFundingService.key(source, FundingSnapshot.EXTRACTOR_VERSION);
        source.setLegiscanId(234);
        assertFalse(interp.getFunding().appliesTo(source));
        assertNotEquals(originalKey, BillFundingService.key(source, FundingSnapshot.EXTRACTOR_VERSION));
    }
}
