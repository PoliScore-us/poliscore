package us.poliscore.referendum;
import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import us.poliscore.model.referendum.Referendum;
class ColoradoReferendumFinanceTest {
    static Referendum measure() {
        var r = new Referendum(); r.setElectionDate(LocalDate.of(2026,11,3));
        var s = new Referendum.Stakeholder(); s.setName("Example Committee"); s.setRole("REGISTERED_COMMITTEE"); r.getStakeholders().add(s); return r;
    }
    @Test void excludesSupersededUnitemizedAndLlcRowsFromDonorEvidence() throws Exception {
        String csv = """
            CO_ID,RecordID,CommitteeName,CommitteeType,ContributionAmount,ContributionDate,LastName,FirstName,ContributionType,Amended,AmendedRecordID
            123,1,EXAMPLE COMMITTEE,Issue Committee,100,2026-01-01 00:00:00,Company,,Monetary (Itemized),Y,
            123,2,EXAMPLE COMMITTEE,Issue Committee,75,2026-01-01 00:00:00,Company,,Monetary (Itemized),N,1
            123,3,EXAMPLE COMMITTEE,Issue Committee,25,2026-01-02 00:00:00,Company,,Returned Contributions,N,
            123,4,EXAMPLE COMMITTEE,Issue Committee,1000,2026-01-03 00:00:00,Unitemized,,Monetary (Non-Itemized),N,
            123,5,EXAMPLE COMMITTEE,Issue Committee,1000,2026-01-03 00:00:00,Allocation,,Monetary (Itemized) - LLC Contribution (Total Amount: 1000.00),N,
            456,6,Other Committee,Issue Committee,500,2026-01-03 00:00:00,Other,,Monetary (Itemized),N,
            """;
        var r = measure(); ColoradoReferendumFinance.parse(new StringReader(csv), 2026, List.of(r), "hash");
        assertEquals(3, r.getFunding().size()); assertTrue(r.getFunding().get(0).isSuperseded());
        assertEquals(BigDecimal.valueOf(50), r.getFundingTotals().get("Company → EXAMPLE COMMITTEE"));
        assertEquals(1, r.getReferences().size());
    }
    @Test void missingFinanceSourceDoesNotClaimZeroFunding() {
        var r = measure(); ColoradoReferendumFinance.fetch(List.of(r), new ReferendumDocuments() {
            @Override public byte[] get(String url) throws Exception { throw new java.io.IOException("offline"); }
        });
        assertEquals("SOURCE_UNAVAILABLE", r.getFundingResearchStatus()); assertTrue(r.getFundingResearchNote().contains("incomplete"));
    }
}
