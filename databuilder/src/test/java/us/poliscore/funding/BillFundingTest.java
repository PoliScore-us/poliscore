package us.poliscore.funding;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class BillFundingTest {
    @Test
    void federalTransferQualifierInheritsOfficeAndIgnoresSublimits() {
        var rows = BillFunding.extract("""
            <division><header>Agriculture Appropriations Act, 2026</header>
            <appropriations-major><header>Department of Agriculture</header></appropriations-major>
            <appropriations-intermediate><header>Office of the Secretary</header></appropriations-intermediate>
            <appropriations-small><header>(INCLUDING TRANSFERS OF FUNDS)</header>
            <text>For necessary expenses of the Office of the Secretary, $46,361,000 of which not to exceed $7,000,000 shall be available.</text>
            </appropriations-small></division>""");
        assertEquals(1, rows.size());
        assertEquals("Department of Agriculture / Office of the Secretary", rows.getFirst().label());
        assertEquals(46361000L, rows.getFirst().amount());
        assertEquals(2026, rows.getFirst().year());
    }

    @Test
    void stateTotalsStopBeforeAmendmentsAndExcludeFundBreakdowns() {
        var rows = BillFunding.extract("""
            For the fiscal year beginning July 1, 2025
            SECTION 2. Appropriation.
            TOTALS PART I (AGRICULTURE) $1,234 $500
            SECTION 3. Amendments.
            TOTALS PART I (AGRICULTURE) $9,999""");
        assertEquals(1, rows.size());
        assertEquals(1234, rows.getFirst().amount());
        assertEquals(2025, rows.getFirst().year());
    }

    @Test
    void recognizesExplicitStateGrantAndSkipsAuthorizationCeilings() {
        assertEquals(3000, BillFunding.extract("The sum of $3,000 is appropriated to the department of health for allocation to counties.").getFirst().amount());
        assertTrue(BillFunding.extract("There is authorized to be appropriated up to $3,000.").isEmpty());
    }
    @Test
    void linksAnExplicitlyCitedPriorActEvenWithoutAnAccountAmount() {
        String current = BillFunding.citationText("At the rate provided by the Full-Year Continuing Appropriations Act, 2025.");
        String prior = "<division><header>Full-Year Continuing Appropriations Act, 2025</header></division>";
        assertTrue(BillFunding.citesPriorAppropriation(current, prior, java.util.Set.of(2025)));
        assertFalse(BillFunding.citesPriorAppropriation(current, prior, java.util.Set.of(2024)));
    }

    @Test
    void comparesAdvanceAppropriationsToExplicitPriorAccountLevels() {
        var prior = BillFunding.extract("""
            <division><header>Full-Year Continuing Appropriations Act, 2025</header><section>
            <text>The levels for each of the following accounts for fiscal year 2026 shall be as follows:</text>
            <subsection><text>$75,039,000,000 for <quote>Department of Veterans Affairs—Medical Services</quote>.</text></subsection>
            </section></division>""").getFirst();
        var current = BillFunding.extract("""
            <division><header>Veterans Affairs Appropriations Act, 2026</header>
            <appropriations-major><header>Department of Veterans Affairs</header></appropriations-major>
            <appropriations-intermediate><header>Veterans health administration</header></appropriations-intermediate>
            <appropriations-small><header>Medical Services</header><text>For necessary expenses for medical services,
            $59,858,000,000, plus reimbursements, which shall become available on October 1, 2026.</text>
            </appropriations-small></division>""");
        var baseline = new us.poliscore.model.bill.FundingSnapshot.FundingRow(prior.label(), prior.amount(), null, "previous", prior.year(), prior.evidence());
        var match = BillFunding.compareFunding(current, List.of(baseline)).getFirst();
        assertEquals(2027, match.year());
        assertEquals(75039000000L, match.prior());
        assertEquals("previous", match.priorBill());
        assertNull(BillFunding.compareFunding(current, List.of(baseline, baseline)).getFirst().prior());
    }
}
