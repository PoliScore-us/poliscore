package us.poliscore.model.referendum;

import java.time.Instant;
import java.util.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import us.poliscore.model.IssueStats;

@Data
@RegisterForReflection
public class ReferendumAnalysis {
    private String supplementalResearch;
    private String title, neutralSummary, approvalEffect, rejectionEffect, forArgument, againstArgument;
    private String ourTake, whoIsBehindIt, structuralAnalysis, impactAnalysis, longReport, casualReport, shortReport;
    private List<String> topics = new ArrayList<>();
    private List<String> otherNames = new ArrayList<>();
    private Integer rating, confidence;
    private IssueStats issueStats = new IssueStats();
    private List<Referendum.Reference> references = new ArrayList<>();
    private String inputFingerprint, promptVersion, model;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant generatedAt;
    private String backingInputFingerprint;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant backingUpdatedAt;
}
