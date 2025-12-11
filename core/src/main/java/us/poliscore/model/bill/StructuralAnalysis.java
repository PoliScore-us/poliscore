package us.poliscore.model.bill;

public enum StructuralAnalysis {
    PROBLEM_CLARITY_CAUSAL_VALIDITY(1, "Problem Clarity & Causal Validity"),
    EVIDENCE_BASE_EMPIRICAL_SUPPORT(2, "Evidence Base & Empirical Support"),
    IMPLEMENTATION_FEASIBILITY(3, "Implementation Feasibility"),
    ECONOMIC_EFFICIENCY_FISCAL_SUSTAINABILITY(4, "Economic Efficiency & Fiscal Sustainability"),
    DISTRIBUTIONAL_IMPACT_FAIRNESS(5, "Distributional Impact & Fairness"),
    GOVERNANCE_INTEGRITY_INSTITUTIONAL_RISK(6, "Governance Integrity & Institutional Risk"),
    UNINTENDED_CONSEQUENCES_SYSTEMIC_RISK(7, "Unintended Consequences & Systemic Risk");

    private final int number;
    private final String displayName;

    StructuralAnalysis(int number, String displayName) {
        this.number = number;
        this.displayName = displayName;
    }

    public int getNumber() {
        return number;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static StructuralAnalysis fromNumber(int n) {
        for (StructuralAnalysis sa : values()) {
            if (sa.number == n) return sa;
        }
        throw new IllegalArgumentException("Unknown StructuralAnalysis pillar number: " + n);
    }
}
