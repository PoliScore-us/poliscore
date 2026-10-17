package us.poliscore.referendum;
import java.math.BigDecimal;
import java.util.*;
import us.poliscore.model.referendum.Referendum;

public final class ReferendumFunding {
    private ReferendumFunding() { }
    /** Totals only comparable contribution records. Superseded filings and transfers are excluded. */
    public static Map<String, BigDecimal> contributionTotals(List<Referendum.Funding> records) {
        var seen = new HashSet<String>(); var totals = new TreeMap<String, BigDecimal>();
        for (var record : records) {
            if (record.isSuperseded() || !Set.of("CONTRIBUTION", "REFUND").contains(record.getTransactionType())) continue;
            if (record.getTransactionId() == null || record.getReferenceId() == null || record.getAmount() == null
                || record.getContributor() == null || record.getCommittee() == null) throw new IllegalArgumentException("Incomplete financial evidence");
            if (!seen.add(record.getTransactionId())) throw new IllegalArgumentException("Duplicate transaction: " + record.getTransactionId());
            BigDecimal amount = record.getAmount();
            if (amount.signum() < 0) throw new IllegalArgumentException("Funding amounts must be positive; use REFUND for refunds");
            if (record.getTransactionType().equals("REFUND")) amount = amount.negate();
            totals.merge(record.getContributor() + " → " + record.getCommittee(), amount, BigDecimal::add);
        }
        return totals;
    }
}
