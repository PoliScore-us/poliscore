package us.poliscore.model.bill;

public enum StructuralAnalysis {
    PRECISION(1, "Precision"),
    EVIDENCE(2, "Evidence"),
    FEASIBILITY(3, "Feasibility"),
    BUDGET(4, "Budget"),
    FAIRNESS(5, "Fairness"),
    GOVERNANCE(6, "Governance"),
    RISK(7, "Risk");

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
