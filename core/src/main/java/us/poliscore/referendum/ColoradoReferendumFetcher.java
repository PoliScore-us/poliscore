package us.poliscore.referendum;

import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.referendum.Referendum;

@ApplicationScoped
public class ColoradoReferendumFetcher implements ReferendumSourceFetcher {
    public JsonNode manifest(LocalDate date) throws Exception {
        try (var stream = getClass().getResourceAsStream("/referendums/co-" + date + ".json")) {
            if (stream == null) throw new IllegalArgumentException("No reviewed Colorado manifest for " + date);
            var manifest = PoliscoreUtil.getObjectMapper().readTree(stream);
            if (!date.toString().equals(manifest.path("electionDate").asText())) throw new IllegalArgumentException("Unsupported election date");
            return manifest;
        }
    }
    @Override public boolean supports(String namespace, LocalDate date) {
        return "us/co".equals(namespace) && date != null && getClass().getResource("/referendums/co-" + date + ".json") != null;
    }
    @Override public List<Referendum> fetch(LocalDate date) throws Exception {
        return fetch(date, new ReferendumDocuments());
    }
    public List<Referendum> fetch(LocalDate date, ReferendumDocuments documents) throws Exception {
        var manifest = manifest(date);
        String inventory = new String(documents.get(manifest.path("inventoryUrl").asText()), StandardCharsets.UTF_8);
        var result = parseInventory(inventory, manifest);
        byte[] book = documents.get(manifest.path("blueBookUrl").asText());
        for (int i = 0; i < result.size(); i++) {
            var measure = result.get(i); var entry = manifest.path("measures").get(i);
            String legalUrl = entry.path("legalTextUrl").asText();
            measure.setOfficialSourceUrl(legalUrl);
            measure.setLegalText(documents.pdf(documents.get(legalUrl)));
            if (measure.getLegalText().length() < 100) throw new IllegalStateException("Missing legal text: " + measure.getId());
            addReference(measure, "legal", legalUrl, "Official legal text", "LEGAL", measure.getLegalText(), "Complete document");
            int start = entry.path("analysisStartPage").asInt(), end = entry.path("analysisEndPage").asInt();
            String analysis = documents.pdf(book, start, end);
            if (!analysis.contains(measure.getDesignation())) throw new IllegalStateException("Blue Book range mismatch");
            measure.setBlueBookAnalysis(analysis);
            measure.setBlueBookFor(between(analysis, "Argument[s]? For", "Argument[s]? Against"));
            measure.setBlueBookAgainst(between(analysis, "Argument[s]? Against", "Fiscal Impact"));
            int fiscal = analysis.indexOf("Fiscal Impact");
            measure.setFiscalAssessment(fiscal < 0 ? null : analysis.substring(fiscal));
            if (measure.getBlueBookFor() == null || measure.getBlueBookAgainst() == null || fiscal < 0)
                throw new IllegalStateException("Incomplete Blue Book sections for " + measure.getDesignation());
            addReference(measure, "bluebook", manifest.path("blueBookUrl").asText(), "Colorado Blue Book: " + measure.getDesignation(),
                "GOVERNMENT", analysis, "PDF pages " + start + "–" + end);
            measure.setSourceFingerprint(fingerprint(measure));
            measure.validate();
        }
        ColoradoReferendumFinance.fetch(result, documents);
        return result;
    }
    public List<Referendum> parseInventory(String html, JsonNode manifest) {
        var document = Jsoup.parse(html, manifest.path("inventoryUrl").asText());
        var sections = new LinkedHashMap<String, Element>();
        for (var heading : document.select("h3")) {
            if (!heading.text().matches("(Amendment|Proposition) [A-Z0-9]+")) continue;
            var section = new Element("section");
            for (var node = heading.nextElementSibling(); node != null && !node.tagName().equals("h3"); node = node.nextElementSibling()) section.appendChild(node.clone());
            sections.put(heading.text(), section);
        }
        var expected = new LinkedHashSet<String>(); manifest.path("measures").forEach(m -> expected.add(m.path("designation").asText()));
        if (!sections.keySet().equals(expected)) throw new IllegalStateException("Certified inventory changed; review manifest before building: " + sections.keySet());
        var result = new ArrayList<Referendum>(); int order = 0;
        for (var entry : manifest.path("measures")) {
            String designation = entry.path("designation").asText(); var section = sections.get(designation);
            var measure = new Referendum(); measure.setNamespace("us/co"); measure.setJurisdictionId("us/co");
            measure.setElectionDate(LocalDate.parse(manifest.path("electionDate").asText()));
            measure.setDesignation(designation); measure.setId(Referendum.generateId("us/co", measure.getElectionDate(), designation));
            measure.setOfficialTitle(entry.path("title").asText()); measure.setBallotOrder(++order);
            measure.setMeasureType(designation.startsWith("Amendment") ? "CONSTITUTIONAL_AMENDMENT" : "PROPOSITION");
            for (var p : section.select("p")) {
                if (p.text().startsWith("Requires ") || p.text().startsWith("Passes with")) measure.setPassageThreshold(p.text());
                if (p.text().toLowerCase(Locale.ROOT).startsWith("shall ")) measure.setBallotQuestion(p.text());
            }
            for (var row : section.select("table tr"))
                measure.getBallotQuestionTable().add(row.select("th, td").eachText());
            if (measure.getPassageThreshold() == null && entry.hasNonNull("passageThreshold")) measure.setPassageThreshold(entry.path("passageThreshold").asText() + " (" + entry.path("passageThresholdSource").asText() + ")");
            if (measure.getBallotQuestion() == null || measure.getPassageThreshold() == null) throw new IllegalStateException("Missing official ballot data: " + designation);
            var h4 = section.selectFirst("h4");
            if (h4 != null) measure.getSourceIdentifiers().add(h4.text());
            if (h4 != null && h4.text().startsWith("Senate Bill")) measure.getOriginatingLegislation().add(h4.text());
            measure.setOfficialSourceUrl(entry.path("legalTextUrl").asText());
            measure.setFetchedAt(Instant.now()); measure.setLastUpdate(measure.getFetchedAt());
            addReference(measure, "inventory", manifest.path("inventoryUrl").asText(), "Certified measures and registered issue committees",
                "GOVERNMENT", section.text(), designation);
            // Keep only names and roles; do not reproduce personal addresses or phone numbers.
            for (var heading : section.select("h5")) {
                Element container = heading.parent();
                if (heading.text().equals("Designated representatives")) {
                    var representatives = heading.nextElementSibling();
                    if (representatives != null) for (var p : representatives.select("p")) addStakeholder(measure, p, "PROPONENT", "For");
                } else if (heading.text().equals("Registered issue committees")) {
                    var committeeContainer = heading.nextElementSibling();
                    if (committeeContainer == null) continue;
                    for (var column : committeeContainer.select("div.w3-col")) {
                        var side = column.selectFirst("strong");
                        String stance = side == null ? "Unknown" : side.text().equalsIgnoreCase("Favor") ? "For" : side.text().equalsIgnoreCase("Oppose") ? "Against" : "Unknown";
                        for (var p : column.select("p")) addStakeholder(measure, p, "REGISTERED_COMMITTEE", stance);
                    }
                }
            }
            measure.setFundingResearchNote("Registered committees are listed by the Secretary of State. Donation amounts have not yet been established from filings.");
            result.add(measure);
        }
        return result;
    }
    private static void addStakeholder(Referendum measure, Element p, String role, String stance) {
        String name = p.wholeText().split("\n")[0].strip();
        if (name.isBlank() || name.equals("Favor") || name.equals("Oppose") || name.contains("Registered agent:")) return;
        var value = new Referendum.Stakeholder(); value.setName(name); value.setRole(role); value.setStance(stance); value.setReferenceId("inventory");
        if (measure.getStakeholders().stream().noneMatch(s -> s.getName().equals(name) && s.getRole().equals(role))) measure.getStakeholders().add(value);
    }
    static String between(String text, String start, String end) {
        var pattern = java.util.regex.Pattern.compile("(?s)" + start + "[^\n]*\n(.*?)(?=" + end + ")");
        var match = pattern.matcher(text); return match.find() ? match.group(1).strip() : null;
    }
    private static void addReference(Referendum measure, String id, String url, String title, String type, String evidence, String location) {
        var ref = new Referendum.Reference(); ref.setId(id); ref.setUrl(url); ref.setTitle(title);
        ref.setAuthor(id.equals("inventory") ? "Colorado Secretary of State" : "Colorado General Assembly");
        ref.setType(type); ref.setStance("Neutral"); ref.setEvidence(evidence); ref.setSummary(title);
        ref.setLocation(location); ref.setRetrievedAt(measure.getFetchedAt()); ref.setContentHash(ReferendumDocuments.hash(evidence)); measure.getReferences().add(ref);
    }
    public static String fingerprint(Referendum measure) {
        return ReferendumDocuments.hash(String.join("\n", Objects.toString(measure.getLegalText(), ""), Objects.toString(measure.getBallotQuestion(), ""),
            Objects.toString(measure.getPassageThreshold(), ""), Objects.toString(measure.getBlueBookAnalysis(), ""), measure.getBallotQuestionTable().toString()));
    }
}
