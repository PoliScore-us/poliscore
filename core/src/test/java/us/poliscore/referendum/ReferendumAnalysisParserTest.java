package us.poliscore.referendum;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.TrackedIssue;
class ReferendumAnalysisParserTest {
    static String valid() throws Exception {
        var m = PoliscoreUtil.getObjectMapper(); var n = m.createObjectNode();
        for (String key : java.util.List.of("title", "neutralSummary", "approvalEffect", "rejectionEffect", "forArgument", "againstArgument", "ourTake", "whoIsBehindIt", "structuralAnalysis", "impactAnalysis", "longReport", "casualReport", "shortReport")) n.put(key, "Evidence-backed assessment");
        n.put("supplementalResearch", "1. Proponent Reasoning: Published case.\n2. Opposition Reasoning: Published objections.\n3. Budget Report: Fiscal findings.\n4. Media Analysis: Reporting.\n5. Broader Context: Existing law.");
        n.put("structuralAnalysis", "1. Precision: Finding. <PASS>\n2. Evidence: Finding. <FAIL>\n3. Feasibility: Finding. <PASS>\n4. Budget: Finding. <PASS>\n5. Fairness: Finding. <PASS>\n6. Governance: Finding. <PASS>\n7. Risk: Finding. <FAIL>");
        n.put("impactAnalysis", "1. Baseline: Current policy. CURRENT_LAW\n2. Affected Parties: Residents. BROAD\n3. Directionality: Evidence. MIXED\n4. Effect Magnitude (per affected person): Costs. MINOR\n5. Temporal Horizon: Implementation. IMMEDIATE\n6. Risk Structure: Administration. REVERSIBLE_EFFECTS\n7. Reversibility: Statutory change. EASILY_REVERSIBLE\n8. Summary: Evidence and uncertainty.");
        n.put("rating", 30); n.put("confidence", 70); n.putArray("topics"); n.putArray("otherNames"); n.putArray("references");
        n.putObject("issueStats").putObject("stats").put(TrackedIssue.OverallBenefitToSociety.name(), 10);
        return n.toString();
    }
    @Test void acceptsCompleteAnalysisAndRejectsMissingSectionsAndBadScores() throws Exception {
        assertEquals(30, ReferendumAnalysisParser.parse(valid()).getRating());
        var bad = (com.fasterxml.jackson.databind.node.ObjectNode) PoliscoreUtil.getObjectMapper().readTree(valid()); bad.put("rating", 300);
        assertThrows(IllegalArgumentException.class, () -> ReferendumAnalysisParser.parse(bad.toString()));
        assertThrows(IllegalArgumentException.class, () -> ReferendumAnalysisParser.parse("{}"));
    }
    @Test void rejectsUnreferencedCitations() throws Exception {
        var n = PoliscoreUtil.getObjectMapper().readTree(valid()); ((com.fasterxml.jackson.databind.node.ObjectNode)n).put("ourTake", "[Study](https://example.org/study)");
        assertThrows(IllegalArgumentException.class, () -> ReferendumAnalysisParser.parse(n.toString()));
    }
    @Test void rejectsIncompleteRubricsAndInvalidImpactStatistics() throws Exception {
        for (String field : java.util.List.of("supplementalResearch", "structuralAnalysis", "impactAnalysis")) {
            var n = (com.fasterxml.jackson.databind.node.ObjectNode) PoliscoreUtil.getObjectMapper().readTree(valid());
            n.put(field, "Placeholder assessment");
            assertThrows(IllegalArgumentException.class, () -> ReferendumAnalysisParser.parse(n.toString()));
        }
        var n = (com.fasterxml.jackson.databind.node.ObjectNode) PoliscoreUtil.getObjectMapper().readTree(valid());
        n.put("impactAnalysis", n.path("impactAnalysis").asText().replace("EASILY_REVERSIBLE", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> ReferendumAnalysisParser.parse(n.toString()));
        var fractionalNode = (com.fasterxml.jackson.databind.node.ObjectNode) PoliscoreUtil.getObjectMapper().readTree(valid());
        ((com.fasterxml.jackson.databind.node.ObjectNode)fractionalNode.path("issueStats").path("stats")).put("OverallBenefitToSociety", 1.5);
        String fractional = fractionalNode.toString();
        assertThrows(IllegalArgumentException.class, () -> ReferendumAnalysisParser.parse(fractional));
    }
    @Test void promptRetainsBillRubricAndRequiresVoteEffectsAndUnknownBacking() {
        var prompt = ReferendumPrompt.build(); assertTrue(prompt.contains("1. Precision:")); assertTrue(prompt.contains("7. Risk:"));
        assertTrue(prompt.contains("8. Summary:")); assertTrue(prompt.contains("1. Proponent Reasoning:"));
        assertFalse(prompt.contains("override conflicting")); assertFalse(prompt.contains("{search"));
        assertTrue(prompt.contains("approvalEffect")); assertTrue(prompt.contains("Missing evidence is not zero funding"));
    }
}
