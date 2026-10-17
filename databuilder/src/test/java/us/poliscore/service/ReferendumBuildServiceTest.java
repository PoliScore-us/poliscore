package us.poliscore.service;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import us.poliscore.model.*;
import us.poliscore.model.referendum.*;
import us.poliscore.referendum.*;
import us.poliscore.entrypoint.DatabaseBuilderConfig;
import us.poliscore.service.storage.*;

class ReferendumBuildServiceTest {
    static Referendum value() {
        var r = new Referendum(); r.setNamespace("us/co"); r.setElectionDate(LocalDate.of(2026,11,3));
        r.setDesignation("Amendment 81"); r.setId(Referendum.generateId("us/co", r.getElectionDate(), r.getDesignation()));
        r.setOfficialSourceUrl("https://leg.colorado.gov/official"); r.setFetchedAt(Instant.now()); r.setSourceFingerprint("new-source");
        return r;
    }
    static class Store extends LocalCachedS3Service {
        Referendum saved;
        @Override public <T extends Persistable> Optional<T> get(String id, Class<T> type) { return Optional.ofNullable(type.cast(saved)); }
        @Override public void put(Persistable obj) { saved = (Referendum)obj; }
    }
    static ReferendumBuildService service(Referendum incoming, Store store, OpenAIService ai) {
        var service = new ReferendumBuildService() {
            @Override protected ReferendumSourceFetcher sourceFor(String namespace, LocalDate date) {
                return new ColoradoReferendumFetcher() { @Override public List<Referendum> fetch(LocalDate ignored) { return List.of(incoming); } };
            }
        }; service.s3 = store; service.ai = ai; service.config = new DatabaseBuilderConfig();
        service.postgres = new PostgresPersistenceService() { @Override public boolean isEnabled() { return false; } };
        return service;
    }
    static ReferendumAnalysis prior(Referendum incoming) {
        var a = new ReferendumAnalysis(); a.setRating(60); a.setInputFingerprint("old-input");
        a.setBackingInputFingerprint(ReferendumResearch.backingFingerprint(incoming)); return a;
    }
    @Test void sourceOnlyBuildRetainsAnalysisAndMarksChangedPolicyStale() {
        var incoming = value(); var store = new Store(); store.saved = value(); store.saved.setAnalysis(prior(incoming));
        var service = service(incoming, store, null); service.process("co:2026-11-03", new BuildReport());
        assertEquals(60, store.saved.getAnalysis().getRating()); assertTrue(store.saved.isAnalysisStale());
    }
    @Test void failedRegenerationPreservesPreviousSuccessfulVersion() {
        var incoming = value(); var store = new Store(); store.saved = value(); store.saved.setAnalysis(prior(incoming));
        var service = service(incoming, store, new OpenAIService() {
            @Override public ChatResult chat(us.poliscore.bill.InterpretationRequest request) { throw new IllegalArgumentException("test failure"); }
        });
        service.config.setInterpretReferendums(true); service.config.setRefreshStaleReferendumAnalyses(true);
        var report = new BuildReport(); service.process("co:2026-11-03", report);
        assertEquals(60, store.saved.getAnalysis().getRating()); assertTrue(report.hasRecovered());
    }
    @Test void failedInitialAnalysisRemainsAbsentAndReportsFailure() {
        var incoming = value(); incoming.setOfficialTitle("Initiative #177 - Right to Natural Gas");
        var store = new Store();
        var service = service(incoming, store, new OpenAIService() {
            @Override public ChatResult chat(us.poliscore.bill.InterpretationRequest request) {
                throw new IllegalArgumentException("Citation missing reference");
            }
        });
        service.config.setInterpretReferendums(true);
        var report = new BuildReport(); service.process("co:2026-11-03", report);
        assertNull(store.saved.getAnalysis());
        assertTrue(report.getReferendumEvents().stream().anyMatch(event ->
            event.contains("Initiative #177") && event.contains("Citation missing reference")));
    }
    @Test void legacyDiagnosticIsDiscardedOnReadAndWrite() throws Exception {
        var mapper = us.poliscore.PoliscoreUtil.getObjectMapper();
        var legacy = mapper.valueToTree(value());
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).put("lastAnalysisError", "old diagnostic");
        var restored = mapper.treeToValue(legacy, Referendum.class);
        assertFalse(mapper.valueToTree(restored).has("lastAnalysisError"));
    }
    @Test void backingRefreshDoesNotChangePolicyScores() {
        var incoming = value(); var store = new Store(); store.saved = value(); var prior = prior(incoming);
        prior.setInputFingerprint(ReferendumBuildService.analysisFingerprint(incoming)); prior.setBackingInputFingerprint("older-research");
        store.saved.setAnalysis(prior);
        var service = service(incoming, store, new OpenAIService() {
            @Override public ChatResult chat(us.poliscore.bill.InterpretationRequest request) {
                return new ChatResult("{\"whoIsBehindIt\":\"Backing not established from available filings.\",\"references\":[]}", new Usage(10,10,"default"),0);
            }
        });
        service.config.setInterpretReferendums(true); service.process("co:2026-11-03", new BuildReport());
        assertEquals(60, store.saved.getAnalysis().getRating()); assertEquals(1, store.saved.getAnalysisHistory().size());
        assertFalse(store.saved.isBackingStale()); assertTrue(store.saved.getAnalysis().getWhoIsBehindIt().contains("not established"));
    }
}
