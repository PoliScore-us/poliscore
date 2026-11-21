package us.poliscore.model.bill;

import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties("sourceUrl") // Older state snapshots included the page-level recovery URL.
public record FundingSnapshot(int extractorVersion, String version, String sourceRevision, Integer sourceDocumentId,
        java.time.LocalDateTime sourceUpdated, long computedAt,
        List<FundingRow> rows, List<String> citedPriorBills, boolean verifiedEmpty) {
    public static final int EXTRACTOR_VERSION = 2;
    public static final int STATE_BUDGET_EXTRACTOR_VERSION = 4;

    @RegisterForReflection
    public record FundingRow(String label, long current, Long prior, String priorBill, int year, String evidence) {}

    public static String revision(BillText text) {
        return Objects.toString(text.getLegiscanId(), "-") + "_" + Objects.toString(text.getLastUpdate(), "-");
    }

    public boolean appliesTo(BillText text) {
        return (extractorVersion == EXTRACTOR_VERSION || extractorVersion == STATE_BUDGET_EXTRACTOR_VERSION)
                && rows != null && (!rows.isEmpty() || verifiedEmpty) && citedPriorBills != null
                && Objects.equals(version, text.getVersion()) && Objects.equals(sourceRevision, revision(text));
    }
}
