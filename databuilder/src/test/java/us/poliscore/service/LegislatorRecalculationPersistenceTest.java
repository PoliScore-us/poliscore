package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import us.poliscore.*;
import us.poliscore.model.*;
import us.poliscore.model.legislator.*;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.service.storage.LocalCachedS3Service;

class LegislatorRecalculationPersistenceTest {
    static class Store extends LocalCachedS3Service {
        String json;
        int writes;
        @Override public <T extends Persistable> Optional<T> get(String id, Class<T> type) {
            if (json == null) return Optional.empty();
            try { return Optional.of(PoliscoreUtil.getObjectMapper().readValue(json, type)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public void put(Persistable value) {
            try { json = PoliscoreUtil.getObjectMapper().writeValueAsString(value); writes++; }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Test void recalculationUpdatesBothViewsWithoutUploadingTheSavedReport() throws Exception {
        var store = new Store();
        var old = new LegislatorInterpretation(LegislativeNamespace.US_CONGRESS, "119", "test", metadata(), null);
        var narrativeDate = LocalDateTime.of(2026, 1, 1, 0, 0);
        old.setLastUpdate(narrativeDate);
        old.setLongExplain("Existing narrative remains unchanged.");
        store.put(old);
        store.writes = 0;
        String savedReport = store.json;
        var service = service(store);
        var dataset = dataset();
        service.recalculateLegislators(dataset);
        var recalculated = dataset.query(Legislator.class).getFirst().getInterpretation();
        assertEquals(20, recalculated.getIssueStats().getStat(TrackedIssue.OverallBenefitToSociety));
        assertEquals(20, recalculated.getRealizedIssueStats().getStat(TrackedIssue.OverallBenefitToSociety));
        assertEquals(old.getLongExplain(), recalculated.getLongExplain());
        assertEquals(narrativeDate, recalculated.getLastUpdate());
        assertEquals(0, store.writes);
        assertEquals(savedReport, store.json);
        service.recalculateLegislators(dataset());
        assertEquals(0, store.writes, "Recalculating a freshly imported dataset must not upload reports either");
    }

    @Test void doesNotPersistAnIncompleteNewReport() throws Exception {
        var store = new Store();
        var dataset = dataset();
        service(store).recalculateLegislators(dataset);
        assertEquals(0, store.writes);
    }

    @Test void disabledFlagSkipsRealizedCalculationAndClearsPreviouslySavedStats() throws Exception {
        var store = new Store();
        var old = new LegislatorInterpretation(LegislativeNamespace.US_CONGRESS, "119", "test", metadata(), null);
        old.setRealizedIssueStats(new IssueStats());
        store.put(old);
        store.writes = 0;
        var service = new LegislatorInterpretationService() {
            @Override public void updateInteractionsInterp(Legislator leg) {}
            @Override public void calculateImpactAndRating(Legislator leg, LegislatorInterpretation interp) {}
            @Override public void calculateRealizedStats(Legislator leg, LegislatorInterpretation interp) {
                fail("Disabled flag must skip calculation entirely");
            }
        };
        inject(service, "s3", store);
        inject(service, "runtimeConfig", new us.poliscore.entrypoint.DatabaseBuilderConfig());
        inject(service, "openAiService", new OpenAIService() {
            @Override public AIInterpretationMetadata metadata() { return LegislatorRecalculationPersistenceTest.metadata(); }
        });
        var dataset = dataset();
        service.recalculateLegislators(dataset);
        var interp = dataset.query(Legislator.class).getFirst().getInterpretation();
        assertNotNull(interp.getIssueStats());
        assertNull(interp.getRealizedIssueStats());
        assertEquals(0, store.writes);
    }

    private PoliscoreDataset dataset() {
        var dataset = new PoliscoreDataset(
            new LegislativeSession(true, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31), "119", LegislativeNamespace.US_CONGRESS),
            new PoliscoreDataset.DeploymentConfig(LegislativeNamespace.US_CONGRESS, 2026, 1f, true));
        var leg = new Legislator();
        leg.setId("LEG/us/congress/119/test");
        leg.setName(new Legislator.LegislatorName());
        var interactions = new ArrayList<LegislatorBillInteraction>();
        for (int n = 0; n < 30; n++) {
            var interaction = new LegislatorBillSponsor();
            interaction.setBillId(n == 0 ? "law" : "proposal-" + n);
            interaction.setLegId(leg.getId());
            interaction.setDate(LocalDate.of(2026, 1, 1));
            interaction.setRating(20);
            var stats = new IssueStats();
            stats.setStat(TrackedIssue.OverallBenefitToSociety, 20);
            interaction.setIssueStats(stats);
            interactions.add(interaction);
        }
        leg.setInteractions(interactions);
        dataset.put(leg);
        return dataset;
    }

    static AIInterpretationMetadata metadata() {
        return AIInterpretationMetadata.construct("OpenAI", "test", 1, false);
    }

    private LegislatorInterpretationService service(Store store) throws Exception {
        var service = new LegislatorInterpretationService() {
            @Override public void updateInteractionsInterp(Legislator leg) {}
            @Override public void calculateImpactAndRating(Legislator leg, LegislatorInterpretation interp) {}
            @Override protected boolean isEnactedBill(String id) { return id.equals("law"); }
        };
        inject(service, "s3", store);
        var config = new us.poliscore.entrypoint.DatabaseBuilderConfig();
        config.setIncludeRealizedSocietalImpact(true);
        inject(service, "runtimeConfig", config);
        inject(service, "openAiService", new OpenAIService() {
            @Override public AIInterpretationMetadata metadata() {
                return LegislatorRecalculationPersistenceTest.metadata();
            }
        });
        return service;
    }

    private void inject(Object target, String name, Object value) throws Exception {
        var field = LegislatorInterpretationService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
