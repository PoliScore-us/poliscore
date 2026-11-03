package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import us.poliscore.*;
import us.poliscore.model.*;
import us.poliscore.model.bill.*;
import us.poliscore.model.legislator.*;
import us.poliscore.model.session.SessionInterpretation;
import us.poliscore.service.storage.*;
import us.poliscore.service.storage.repository.*;

class PostgresSyncPublicationTest {
    @Test void publishesLegislatorsBeforeRefreshingRankingsInSmallBatches() throws Exception {
        var events = new ArrayList<String>();
        var chunks = new ArrayList<Integer>();
        var service = service(events, chunks, false);
        service.syncPostgresWithS3(dataset());
        assertTrue(events.indexOf("legislators") < events.indexOf("hot"));
        assertEquals(List.of(100, 100, 5), chunks);
    }

    @Test void rankingFailureOccursAfterLegislatorPublicationAndPropagates() throws Exception {
        var events = new ArrayList<String>();
        var service = service(events, new ArrayList<>(), true);
        assertThrows(IllegalStateException.class, () -> service.syncPostgresWithS3(dataset()));
        assertEquals(List.of("legislators", "hot"), events);
    }

    private PostgresSyncService service(List<String> events, List<Integer> chunks, boolean fail) throws Exception {
        var service = new PostgresSyncService();
        inject(service, "s3", new LocalCachedS3Service() {
            @Override public <T extends Persistable> void optimizeExists(Class<T> type, String key) {}
        });
        inject(service, "billRepository", new BillRepository() {
            @Override public Map<String, java.time.LocalDateTime> getLastUpdateMap(String key) { return Map.of(); }
            @Override public PaginatedList<Bill> query(String key, int size, String index, Boolean asc, String start, String sort) {
                var bills = new ArrayList<Bill>();
                for (int n = 0; n < 205; n++) {
                    var bill = new Bill(); bill.setId("BIL/us/congress/119/hr/" + n); bills.add(bill);
                }
                return new PaginatedList<>(bills, size, start, null);
            }
            @Override public void refreshHotValues(List<Bill> bills) {
                if (!events.contains("hot")) events.add("hot");
                chunks.add(bills.size());
                if (fail) throw new IllegalStateException("Ranking write failed");
            }
        });
        inject(service, "legislatorRepository", new LegislatorRepository() {
            @Override public void putAll(List<Legislator> legs) {
                assertNotNull(legs.getFirst().getInterpretation().getRealizedIssueStats());
                events.add("legislators");
            }
            @Override public int deleteMissingFromStorageBucket(String bucket, Set<String> ids) { return 0; }
        });
        inject(service, "sessionInterpretationRepository", new SessionInterpretationRepository() {
            @Override public void putAll(List<SessionInterpretation> values) {}
        });
        inject(service, "legService", new LegislatorService() {
            @Override public void applyInterpretation(Legislator leg, LegislatorInterpretation interp) {}
        });
        return service;
    }

    private PoliscoreDataset dataset() {
        var dataset = new PoliscoreDataset(new LegislativeSession(true, LocalDate.of(2025,1,1), LocalDate.of(2026,12,31),
            "119", LegislativeNamespace.US_CONGRESS), new PoliscoreDataset.DeploymentConfig(LegislativeNamespace.US_CONGRESS, 2026));
        var leg = new Legislator(); leg.setId("LEG/us/congress/119/test"); leg.setName(new Legislator.LegislatorName());
        leg.setInterpretation(new LegislatorInterpretation()); leg.getInterpretation().setRealizedIssueStats(new IssueStats());
        dataset.put(leg);
        return dataset;
    }

    private void inject(PostgresSyncService target, String name, Object value) throws Exception {
        var field = PostgresSyncService.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
