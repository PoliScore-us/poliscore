package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import us.poliscore.model.IssueStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.VoteStatus;
import us.poliscore.model.bill.BillStatus;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorBillInteraction;
import us.poliscore.model.legislator.LegislatorBillInteraction.*;
import us.poliscore.model.legislator.LegislatorInterpretation;

class LegislatorSocietalImpactTest {
    private final LegislatorInterpretationService service = new LegislatorInterpretationService() {
        @Override protected boolean isEnactedBill(String id) {
            return Set.of("law", "harmful-law", "unscored-law").contains(id);
        }
    };

    @Test void realizedUsesOnlyEnactedScoredBillsAndSignedWeights() {
        var leg = new Legislator();
        var sponsor = interaction(new LegislatorBillSponsor(), "law", 60);
        var duplicate = interaction(new LegislatorBillCosponsor(), "law", 60);
        var opposition = new LegislatorBillVote();
        opposition.setVoteStatus(VoteStatus.NAY);
        interaction(opposition, "harmful-law", -30);
        var absent = new LegislatorBillVote();
        absent.setVoteStatus(VoteStatus.PRESENT);
        interaction(absent, "law", 100);
        var unscored = interaction(new LegislatorBillSponsor(), "unscored-law", 100);
        unscored.setIssueStats(null);
        leg.setInteractions(List.of(sponsor, duplicate, opposition, absent, unscored,
            interaction(new LegislatorBillSponsor(), "proposal", -90)));
        var interp = new LegislatorInterpretation();
        interp.setIssueStats(service.calculateAgregateInteractionStats(leg).toIssueStats());
        assertEquals(-6, interp.getIssueStats().getStat(TrackedIssue.OverallBenefitToSociety));
        service.calculateRealizedStats(leg, interp);
        assertEquals(50, interp.getRealizedIssueStats().getStat(TrackedIssue.OverallBenefitToSociety));
        assertEquals(-6, interp.getIssueStats().getStat(TrackedIssue.OverallBenefitToSociety));
    }

    @Test void noEnactedBillsClearsStaleStatsRatherThanAssigningZero() {
        var leg = new Legislator();
        var interp = new LegislatorInterpretation();
        interp.setRealizedIssueStats(new IssueStats());
        service.calculateRealizedStats(leg, interp);
        assertNull(interp.getRealizedIssueStats());
    }

    @Test void completionAndEnactmentAreDifferent() {
        assertTrue(LegislatorInterpretationService.isEnactedStatus(new BillStatus("Law", 1f, "PASSED")));
        assertTrue(LegislatorInterpretationService.isEnactedStatus(new BillStatus("Veto Overridden", 1f, "OVERRIDE")));
        assertTrue(LegislatorInterpretationService.isEnactedStatus(new BillStatus("Law", 1f, "ENACTED:SIGNED")));
        assertFalse(LegislatorInterpretationService.isEnactedStatus(new BillStatus("Simple Resolution Passed", 1f, "PASSED:SIMPLERES")));
        assertFalse(LegislatorInterpretationService.isEnactedStatus(new BillStatus("Vetoed", .9f, "VETOED")));
        assertFalse(LegislatorInterpretationService.isEnactedStatus(null));
    }

    private <T extends LegislatorBillInteraction> T interaction(T interaction, String billId, int score) {
        interaction.setBillId(billId);
        interaction.setDate(LocalDate.of(2026, 1, 1));
        var stats = new IssueStats();
        stats.setStat(TrackedIssue.OverallBenefitToSociety, score);
        interaction.setIssueStats(stats);
        return interaction;
    }
}
