package us.poliscore.referendum;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.referendum.*;

public final class ReferendumAnalysisParser {
    private ReferendumAnalysisParser() { }
    public static ReferendumAnalysis parse(String json) {
        try {
            var mapper = PoliscoreUtil.getObjectMapper();
            var tree = mapper.readTree(json);
            if (!tree.isObject()) throw new IllegalArgumentException("Expected analysis object");
            for (String key : List.of("supplementalResearch", "title", "neutralSummary", "approvalEffect", "rejectionEffect", "forArgument",
                    "againstArgument", "ourTake", "whoIsBehindIt", "structuralAnalysis", "impactAnalysis", "longReport", "casualReport", "shortReport"))
                if (!tree.path(key).isTextual() || tree.path(key).asText().isBlank()) throw new IllegalArgumentException("Missing " + key);
            for (String key : List.of("topics", "otherNames", "references"))
                if (!tree.path(key).isArray()) throw new IllegalArgumentException("Missing array " + key);
            for (String key : List.of("rating", "confidence"))
                if (!tree.path(key).isIntegralNumber()) throw new IllegalArgumentException("Missing integer " + key);
            validateSections(tree.path("supplementalResearch").asText(), List.of("Proponent Reasoning", "Opposition Reasoning", "Budget Report", "Media Analysis", "Broader Context"), false);
            validateSections(tree.path("structuralAnalysis").asText(), List.of("Precision", "Evidence", "Feasibility", "Budget", "Fairness", "Governance", "Risk"), true);
            validateSections(tree.path("impactAnalysis").asText(), List.of("Baseline", "Affected Parties", "Directionality", "Effect Magnitude (per affected person)", "Temporal Horizon", "Risk Structure", "Reversibility", "Summary"), false);
            String impact = tree.path("impactAnalysis").asText();
            String[][] classifications = {
                {"CURRENT_LAW", "LIKELY_TRAJECTORY", "NO_ACTION", "OTHER"},
                {"VERY_NARROW", "NARROW", "MODERATE", "BROAD", "NEAR_UNIVERSAL"},
                {"NET_BENEFICIAL", "NET_HARMFUL", "MIXED", "UNCLEAR"},
                {"NEGLIGIBLE", "MINOR", "MODERATE", "SIGNIFICANT", "SEVERE"},
                {"IMMEDIATE", "MEDIUM_TERM", "LONG_TERM"},
                {"LINEAR_EFFECTS", "THRESHOLD_DEPENDENT", "TAIL_RISK", "SYSTEMIC_RISK", "IRREVERSIBLE_HARM", "REVERSIBLE_EFFECTS"},
                {"EASILY_REVERSIBLE", "DIFFICULT_TO_REVERSE", "LARGELY_IRREVERSIBLE"}
            };
            for (int i = 0; i < classifications.length; i++) {
                String stage = numberedSection(impact, i + 1);
                if (Arrays.stream(classifications[i]).noneMatch(token -> Pattern.compile("\\b" + token + "\\b").matcher(stage).find()))
                    throw new IllegalArgumentException("Missing impact classification in stage " + (i + 1));
            }
            var stats = tree.path("issueStats").path("stats");
            if (!stats.isObject() || !stats.has("OverallBenefitToSociety")) throw new IllegalArgumentException("Missing overall impact statistic");
            var fields = stats.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                us.poliscore.model.TrackedIssue.valueOf(field.getKey());
                if (!field.getValue().isIntegralNumber() || !field.getValue().canConvertToInt())
                    throw new IllegalArgumentException("Impact statistic must be an integer");
            }
            var result = mapper.treeToValue(tree, ReferendumAnalysis.class);
            if (result.getRating() < -100 || result.getRating() > 100 || result.getConfidence() < 0 || result.getConfidence() > 100)
                throw new IllegalArgumentException("Analysis score out of range");
            if (result.getIssueStats() == null || result.getIssueStats().getStats().isEmpty()) throw new IllegalArgumentException("Missing impact statistics");
            for (var score : result.getIssueStats().getStats().values())
                if (score == null || score < -100 || score > 100) throw new IllegalArgumentException("Impact score out of range");
            validateReferences(json, result.getReferences());
            return result;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid referendum analysis: " + e.getMessage(), e); }
    }
    private static void validateSections(String text, List<String> headings, boolean verdicts) {
        int previous = -1;
        for (int i = 0; i < headings.size(); i++) {
            var heading = Pattern.compile("(?m)^\\s*" + (i + 1) + "\\. " + Pattern.quote(headings.get(i)) + ":").matcher(text);
            if (!heading.find() || heading.start() <= previous) throw new IllegalArgumentException("Missing or unordered section: " + headings.get(i));
            previous = heading.start();
            String body = numberedSection(text, i + 1);
            if (body.isBlank()) throw new IllegalArgumentException("Empty section: " + headings.get(i));
            if (verdicts && !body.strip().matches("(?s).+<(PASS|FAIL)>"))
                throw new IllegalArgumentException("Missing structural verdict: " + headings.get(i));
        }
    }
    private static String numberedSection(String text, int number) {
        var section = Pattern.compile("(?m)^\\s*" + number + "\\. [^\\n:]+:").matcher(text);
        if (!section.find()) throw new IllegalArgumentException("Missing numbered section " + number);
        int start = section.end();
        var next = Pattern.compile("(?m)^\\s*" + (number + 1) + "\\. ").matcher(text);
        return text.substring(start, next.find(start) ? next.start() : text.length());
    }
    public static ReferendumAnalysis parseBacking(String json) {
        try {
            var tree = PoliscoreUtil.getObjectMapper().readTree(json);
            if (!tree.path("whoIsBehindIt").isTextual() || tree.path("whoIsBehindIt").asText().isBlank() || !tree.path("references").isArray())
                throw new IllegalArgumentException("Missing backing research");
            var result = new ReferendumAnalysis(); result.setWhoIsBehindIt(tree.path("whoIsBehindIt").asText());
            result.setReferences(PoliscoreUtil.getObjectMapper().convertValue(tree.path("references"), new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Referendum.Reference>>() {}));
            validateReferences(json, result.getReferences()); return result;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid backing research", e); }
    }
    private static void validateReferences(String json, java.util.List<Referendum.Reference> references) {
            var urls = new HashSet<String>(); var ids = new HashSet<String>();
            for (var ref : references) {
                if (ref.getId() == null || !ids.add(ref.getId()) || ref.getEvidence() == null || ref.getEvidence().isBlank())
                    throw new IllegalArgumentException("Invalid reference");
                URI uri = URI.create(ref.getUrl());
                if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException("Invalid reference URL");
                if (!Set.of("GOVERNMENT", "LEGAL", "BUDGETARY", "STAKEHOLDER", "NEWS", "ACADEMIC", "OTHER").contains(ref.getType())
                        || !Set.of("For", "Against", "Neutral", "Mixed", "N/A").contains(ref.getStance())) throw new IllegalArgumentException("Invalid reference classification");
                urls.add(ref.getUrl());
            }
            var links = Pattern.compile("\\]\\((https?://[^\\s)]+)\\)").matcher(json);
            while (links.find()) if (!urls.contains(links.group(1))) throw new IllegalArgumentException("Citation missing reference: " + links.group(1));
    }

}
