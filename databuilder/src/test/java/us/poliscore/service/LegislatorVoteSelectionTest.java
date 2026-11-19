package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.poliscore.model.IssueStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.VoteStatus;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorBillInteraction;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillCosponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillVote;

class LegislatorVoteSelectionTest {
    private final LegislatorInterpretationService service = new LegislatorInterpretationService();

    @Test void latestSubstantiveVoteDeterminesThirtyBillScoreInEitherOrder() {
        var votes = new ArrayList<LegislatorBillVote>();
        for (int i = 0; i < 30; i++) {
            String bill = "bill-" + i;
            votes.add(vote(bill, 1, VoteStatus.AYE, null));
            votes.add(vote(bill, 2, VoteStatus.NAY, null));
            votes.add(vote(bill, 3, VoteStatus.PRESENT, null));
            votes.add(vote(bill, 4, VoteStatus.NOT_VOTING, null));
        }
        for (var order : List.of(votes, votes.reversed())) {
            var leg = new Legislator();
            order.forEach(leg::addBillInteraction);
            assertTrue(service.meetsInterpretationPrereqs(leg));
            assertEquals(30, service.getInteractionsForInterpretation(leg).size());
            assertEquals(-60, service.calculateAgregateInteractionStats(leg).toIssueStats()
                    .getStat(TrackedIssue.OverallBenefitToSociety));
        }
    }

    @Test void sameDayVotesUseSequenceAndOmitUnresolvableConflicts() {
        var latest = vote("numbered", 1, VoteStatus.AYE, 10);
        var votes = List.of(vote("numbered", 1, VoteStatus.AYE, 8), latest,
                vote("numbered", 1, VoteStatus.NAY, 9),
                vote("legacy", 1, VoteStatus.AYE, null), vote("legacy", 1, VoteStatus.NAY, null),
                vote("mixed", 1, VoteStatus.AYE, 9), vote("mixed", 1, VoteStatus.NAY, null));
        for (var order : List.of(votes, votes.reversed())) {
            var leg = new Legislator();
            order.forEach(leg::addBillInteraction);
            assertEquals(List.of(latest), service.getInteractionsForInterpretation(leg));
        }
    }

    @Test void earlierSponsorshipAndCosponsorshipKeepPrecedence() {
        var sponsor = new LegislatorBillSponsor();
        sponsor.setBillId("a");
        sponsor.setDate(LocalDate.of(2026, 1, 1));
        var cosponsor = new LegislatorBillCosponsor();
        cosponsor.setBillId("b");
        cosponsor.setDate(sponsor.getDate());
        var interactions = List.<LegislatorBillInteraction>of(sponsor, cosponsor,
                vote("a", 2, VoteStatus.NAY, null), vote("b", 2, VoteStatus.NAY, null));
        for (var order : List.of(interactions, interactions.reversed())) {
            var leg = new Legislator();
            leg.setInteractions(order);
            assertEquals(List.of(sponsor, cosponsor), service.getInteractionsForInterpretation(leg));
        }
    }

    private LegislatorBillVote vote(String bill, int day, VoteStatus status, Integer rollCallNumber) {
        var vote = new LegislatorBillVote(status);
        vote.setBillId(bill);
        vote.setDate(LocalDate.of(2026, 1, day));
        vote.setRollCallNumber(rollCallNumber);
        var stats = new IssueStats();
        stats.setStat(TrackedIssue.OverallBenefitToSociety, 60);
        vote.setIssueStats(stats);
        return vote;
    }
}
