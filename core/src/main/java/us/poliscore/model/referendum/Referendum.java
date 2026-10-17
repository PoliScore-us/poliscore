package us.poliscore.model.referendum;

import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import us.poliscore.model.Persistable;

/** An election-owned measure, independent of any legislative session. */
@Data
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class Referendum implements Persistable {
    public static final String ID_CLASS_PREFIX = "REF";
    private String id;
    private String namespace;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private LocalDate electionDate;
    private String jurisdictionScope = "STATE";
    private String jurisdictionId;
    private String designation;
    private String officialTitle;
    private String ballotQuestion;
    private List<List<String>> ballotQuestionTable = new ArrayList<>();
    private String legalText;
    private String officialSourceUrl;
    private String measureType;
    private String status = "CERTIFIED";
    private String passageThreshold;
    private int ballotOrder;
    private List<String> sourceIdentifiers = new ArrayList<>();
    private List<String> originatingLegislation = new ArrayList<>();
    private List<String> implementingLegislation = new ArrayList<>();
    private List<String> relatedMeasures = new ArrayList<>();
    private String blueBookAnalysis;
    private String blueBookFor;
    private String blueBookAgainst;
    private String fiscalAssessment;
    private List<Reference> references = new ArrayList<>();
    private List<Stakeholder> stakeholders = new ArrayList<>();
    private List<Funding> funding = new ArrayList<>();
    private String fundingResearchStatus = "NOT_RESEARCHED";
    private String fundingResearchNote;
    private String sourceFingerprint;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant fetchedAt;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant lastUpdate;
    private ReferendumAnalysis analysis;
    private List<ReferendumAnalysis> analysisHistory = new ArrayList<>();

    public static String generateId(String namespace, LocalDate date, String designation) {
        if (namespace == null || !namespace.matches("us/[a-z]{2}") || date == null
                || designation == null || !designation.matches("[A-Za-z0-9]+(?:[ -][A-Za-z0-9]+)*"))
            throw new IllegalArgumentException("Invalid referendum identity");
        return "REF/" + namespace + "/" + date + "/state/" + designation.toLowerCase(Locale.ROOT).replace(' ', '-');
    }
    @Override @JsonIgnore public String getStorageBucket() {
        return "REF/" + namespace + "/" + electionDate.getYear();
    }
    @Override public void setStorageBucket(String ignored) { }
    @JsonIgnore public String getRouteKey() { return electionDate + "/" + designation.toLowerCase(Locale.ROOT).replace(' ', '-'); }
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public java.util.Map<String, java.math.BigDecimal> getFundingTotals() {
        return us.poliscore.referendum.ReferendumFunding.contributionTotals(funding);
    }
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public boolean isAnalysisStale() {
        return analysis != null && !Objects.equals(analysis.getInputFingerprint(), us.poliscore.referendum.ReferendumDocuments.hash(
            sourceFingerprint + ":" + us.poliscore.referendum.ReferendumPrompt.VERSION));
    }
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public boolean isBackingStale() {
        return analysis != null && !Objects.equals(analysis.getBackingInputFingerprint(), us.poliscore.referendum.ReferendumResearch.backingFingerprint(this));
    }
    @Override public void validate() {
        if (!"STATE".equals(jurisdictionScope)) throw new IllegalArgumentException("Only statewide measures are enabled");
        if (!Objects.equals(id, generateId(namespace, electionDate, designation))) throw new IllegalArgumentException("Referendum ID does not match metadata");
        if (officialSourceUrl == null || !officialSourceUrl.startsWith("https://")) throw new IllegalArgumentException("Official source is required");
    }
    @Data @RegisterForReflection public static class Reference {
        private String id, url, author, title, stance, summary, evidence, type, contentHash, location;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        private Instant retrievedAt;
    }
    @Data @RegisterForReflection public static class Stakeholder {
        private String name, role, stance, referenceId;
    }
    @Data @RegisterForReflection public static class Funding {
        private String transactionId, contributor, committee, transactionType, referenceId, reportingPeriod;
        private java.math.BigDecimal amount;
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        private LocalDate date;
        private boolean superseded;
    }
}
