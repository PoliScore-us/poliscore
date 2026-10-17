package us.poliscore.referendum;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import us.poliscore.model.referendum.Referendum;
class ReferendumFundingTest {
    static Referendum.Funding record(String id, String type, int amount) {
        var f = new Referendum.Funding(); f.setTransactionId(id); f.setContributor("Donor"); f.setCommittee("Committee"); f.setReferenceId("filing");
        f.setAmount(BigDecimal.valueOf(amount)); f.setTransactionType(type); return f;
    }
    @Test void excludesSupersededFilingsAndTransfersAndSubtractsRefunds() {
        var old = record("old", "CONTRIBUTION", 500); old.setSuperseded(true);
        var values = List.of(old, record("new", "CONTRIBUTION", 300), record("refund", "REFUND", 50), record("transfer", "TRANSFER", 200));
        assertEquals(BigDecimal.valueOf(250), ReferendumFunding.contributionTotals(values).get("Donor → Committee"));
    }
    @Test void duplicateTransactionsAreRejectedInsteadOfDoubleCounted() {
        assertThrows(IllegalArgumentException.class, () -> ReferendumFunding.contributionTotals(List.of(record("same", "CONTRIBUTION", 10), record("same", "CONTRIBUTION", 10))));
    }
}
