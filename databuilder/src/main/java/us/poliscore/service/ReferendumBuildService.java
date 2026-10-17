package us.poliscore.service;

import java.time.*;
import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.PoliscoreUtil;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.model.BuildReport;
import us.poliscore.model.referendum.*;
import us.poliscore.referendum.*;
import us.poliscore.entrypoint.DatabaseBuilderConfig;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.PostgresPersistenceService;

@ApplicationScoped
public class ReferendumBuildService {
    @Inject jakarta.enterprise.inject.Instance<ReferendumSourceFetcher> fetchers;
    protected ReferendumSourceFetcher sourceFor(String namespace, LocalDate date) {
        return fetchers.stream().filter(f -> f.supports(namespace, date)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No reviewed source adapter for " + namespace + " " + date));
    }
    @Inject LocalCachedS3Service s3;
    @Inject PostgresPersistenceService postgres;
    @Inject OpenAIService ai;
    @Inject DatabaseBuilderConfig config;

    public void process(String election, BuildReport report) {
        String[] parts = election.split(":", -1);
        if (parts.length != 2 || !parts[0].matches("[a-z]{2}")) throw new IllegalArgumentException("Election must be state:YYYY-MM-DD");
        String namespace = "us/" + parts[0]; LocalDate date = LocalDate.parse(parts[1]);
        var source = sourceFor(namespace, date);
        try {
            var measures = source.fetch(date);
            report.getReferendumEvents().add("Fetched " + measures.size() + " certified measures for " + election);
            for (var value : measures) {
                var existing = s3.get(value.getId(), Referendum.class).orElse(null);
                if (existing != null) {
                    value.setAnalysis(existing.getAnalysis()); value.setAnalysisHistory(existing.getAnalysisHistory());
                }
                String fingerprint = analysisFingerprint(value);
                boolean stale = value.getAnalysis() != null && !fingerprint.equals(value.getAnalysis().getInputFingerprint());
                if (config.isInterpretReferendums() && (value.getAnalysis() == null || (stale && config.isRefreshStaleReferendumAnalyses()))) {
                    try {
                        var evidence = PoliscoreUtil.getObjectMapper().valueToTree(value);
                        ((com.fasterxml.jackson.databind.node.ObjectNode) evidence).remove(List.of("analysis", "analysisHistory", "funding"));
                        var request = InterpretationRequest.builder().data(new CustomData(value.getId()))
                            .systemMsg(ReferendumPrompt.build()).userMsg(evidence.toString())
                            .requestedModel(OpenAIModel.DEFAULT_MODEL).flex(config.isFlexRequests()).build();
                        var response = ai.chat(request);
                        var analysis = ReferendumAnalysisParser.parse(response.content());
                        analysis.setInputFingerprint(fingerprint); analysis.setPromptVersion(ReferendumPrompt.VERSION);
                        analysis.setModel(OpenAIModel.DEFAULT_MODEL.getId()); analysis.setGeneratedAt(Instant.now());
                        analysis.setBackingInputFingerprint(ReferendumResearch.backingFingerprint(value)); analysis.setBackingUpdatedAt(Instant.now());
                        // Snapshot both official inputs and external evidence alongside each interpretation.
                        var references = new LinkedHashMap<String, Referendum.Reference>();
                        value.getReferences().forEach(r -> references.put(r.getId(), r));
                        analysis.getReferences().forEach(r -> {
                            var official = references.get(r.getId());
                            if (official != null && !official.getUrl().equals(r.getUrl())) throw new IllegalArgumentException("Reference ID collision");
                            if (official == null) { r.setRetrievedAt(Instant.now()); references.put(r.getId(), r); }
                        });
                        analysis.setReferences(new ArrayList<>(references.values()));
                        if (value.getAnalysis() != null) value.getAnalysisHistory().add(value.getAnalysis());
                        value.setAnalysis(analysis);
                        report.setTotalProcessingCost(report.getTotalProcessingCost() + response.costUsd());
                        report.getReferendumEvents().add("Analyzed " + value.getId());
                    } catch (Exception e) {
                        String failure = "Analysis failed: " + value.getId() + " (" + value.getOfficialTitle() + "): " + e.getMessage();
                        org.jboss.logging.Logger.getLogger(ReferendumBuildService.class).error(failure, e);
                        report.recovered(e); report.getReferendumEvents().add(failure);
                    }
                } else if (config.isInterpretReferendums() && value.isBackingStale()) {
                    try {
                        var mapper = PoliscoreUtil.getObjectMapper();
                        var inputs = mapper.createObjectNode(); inputs.put("designation", value.getDesignation());
                        inputs.put("electionDate", value.getElectionDate().toString()); inputs.put("ballotQuestion", value.getBallotQuestion());
                        inputs.set("stakeholders", mapper.valueToTree(value.getStakeholders()));
                        inputs.set("contributionTotals", mapper.valueToTree(value.getFundingTotals()));
                        inputs.put("fundingResearchNote", value.getFundingResearchNote()); inputs.put("asOf", value.getFetchedAt().toString());
                        inputs.set("references", mapper.valueToTree(value.getReferences().stream().filter(r -> r.getId().equals("inventory") || r.getId().startsWith("finance-")).toList()));
                        var request = InterpretationRequest.builder().data(new CustomData(value.getId())).systemMsg(ReferendumResearch.backingPrompt())
                            .userMsg(inputs.toString()).requestedModel(OpenAIModel.DEFAULT_MODEL).flex(config.isFlexRequests()).build();
                        var response = ai.chat(request); var research = ReferendumAnalysisParser.parseBacking(response.content());
                        var updated = mapper.readValue(mapper.writeValueAsString(value.getAnalysis()), ReferendumAnalysis.class);
                        updated.setWhoIsBehindIt(research.getWhoIsBehindIt());
                        var refs = new LinkedHashMap<String, Referendum.Reference>();
                        updated.getReferences().forEach(r -> refs.put(r.getId(), r));
                        research.getReferences().forEach(r -> {
                            if (refs.containsKey(r.getId()) && !refs.get(r.getId()).getUrl().equals(r.getUrl())) throw new IllegalArgumentException("Reference ID collision");
                            refs.put(r.getId(), r);
                        });
                        value.getReferences().stream().filter(r -> r.getId().equals("inventory") || r.getId().startsWith("finance-")).forEach(r -> refs.put(r.getId(), r));
                        updated.setReferences(new ArrayList<>(refs.values()));
                        updated.setBackingInputFingerprint(ReferendumResearch.backingFingerprint(value)); updated.setBackingUpdatedAt(Instant.now());
                        value.getAnalysisHistory().add(value.getAnalysis()); value.setAnalysis(updated);
                        report.setTotalProcessingCost(report.getTotalProcessingCost() + response.costUsd());
                        report.getReferendumEvents().add("Updated backing research: " + value.getId());
                    } catch (Exception e) {
                        org.jboss.logging.Logger.getLogger(ReferendumBuildService.class).error("Backing update failed: " + value.getId(), e);
                        report.recovered(e);
                        report.getReferendumEvents().add("Backing update failed: " + value.getId() + ": " + e.getMessage());
                    }
                } else report.getReferendumEvents().add((stale ? "Stale: " : "Stored/unchanged analysis: ") + value.getId());
                boolean unchanged = existing != null
                    && Objects.equals(value.getSourceFingerprint(), existing.getSourceFingerprint())
                    && Objects.equals(ReferendumResearch.backingFingerprint(value), ReferendumResearch.backingFingerprint(existing))
                    && Objects.equals(value.getOfficialTitle(), existing.getOfficialTitle())
                    && Objects.equals(value.getStatus(), existing.getStatus())
                    && Objects.equals(value.getAnalysis(), existing.getAnalysis());
                value.setLastUpdate(unchanged ? existing.getLastUpdate() : Instant.now());
                s3.put(value);
                if (postgres.isEnabled()) postgres.put(value);
            }
        } catch (Exception e) { report.fatal(e); }
    }
    public static String analysisFingerprint(Referendum value) {
        return ReferendumDocuments.hash(value.getSourceFingerprint() + ":" + ReferendumPrompt.VERSION);
    }
}
