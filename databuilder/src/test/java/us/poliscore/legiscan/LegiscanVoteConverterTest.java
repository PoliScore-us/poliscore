package us.poliscore.legiscan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LegiscanVoteConverterTest {

	@Test
	void treatsColoradoCommitteeReportsAsProcedural() {
		assertTrue(LegiscanVoteConverter.isProcedural("House: RESOLUTIONS Comm Report (sa)"));
		assertFalse(LegiscanVoteConverter.isBillVote("House: RESOLUTIONS Comm Report (sa)"));
	}

	@Test
	void treatsColoradoCommitteeItemCodesAsProcedural() {
		assertTrue(LegiscanVoteConverter.isProcedural("Senate Appropriations: J.001"));
		assertFalse(LegiscanVoteConverter.isBillVote("Senate Appropriations: J.001"));
	}

	@Test
	void stillRecognizesColoradoResolutionPassageVotes() {
		assertFalse(LegiscanVoteConverter.isProcedural("House: RESOLUTIONS Res. 001"));
		assertTrue(LegiscanVoteConverter.isBillVote("House: RESOLUTIONS Res. 001"));
	}

	@Test
	void recognizesGeorgiaSubstituteAndConcurrenceVotesAsSubstantive() {
		for (String description : List.of(
				"Agree To House Substitute: Senate Vote #273",
				"Agree To Senate Substitute: House Vote #300",
				"Agree To Senate Sub As Am: House Vote #779",
				"Agree To Senate Am As Am: House Vote #400",
				"Agree To Sam To Hsub: House Vote #360",
				"Agree To Sam To Hsub As Ham: House Vote #361",
				"Uncontested House Resolutions: House Vote #100")) {
			assertFalse(LegiscanVoteConverter.isProcedural(description), description);
			assertTrue(LegiscanVoteConverter.isBillVote(description), description);
		}
	}

	@Test
	void recognizesGeorgiaProceduralRollCalls() {
		for (String description : List.of(
				"Immediately Transmit: House Vote #291",
				"Motion To Immediately Transmit: Senate Vote #42",
				"Motion To Immediately Transmit To Governor: Senate Vote #495",
				"Immediately Transmit To Governor: House Vote #19",
				"Motion To Resolve Debate: Senate Vote #696",
				"Motion To Withdraw And Commit: Senate Vote #718",
				"Motion To Remove From The Table: House Vote #100",
				"Table: Senate Vote #101",
				"Sb614 & Sb627 Recon: House Vote #858",
				"Recon Sb 234, 235, & 336: House Vote #200",
				"Remove From Local Cal/vote Separately: House Vote #845",
				"Move From P&ch To Hth: House Vote #10",
				"Move From Eu&t To Scrm: House Vote #11",
				"Motion To Suspend Senate Rule 7-1-6(b): Senate Vote #12",
				"Motion To Print: Senate Vote #984",
				"Am 62 0077: House Vote #853",
				"Adjournment: House Vote #455")) {
			assertTrue(LegiscanVoteConverter.isProcedural(description), description);
			assertFalse(LegiscanVoteConverter.isBillVote(description), description);
		}
	}

	@Test
	void reviewedSubstantiveDescriptionsOverrideBroadProceduralKeywords() {
		for (String description : List.of(
				"Conference Report Agreed To",
				"Concur In Senate Amendment",
				"Read Third Time And Passed",
				"Suspend The Rules And Pass The Bill")) {
			assertFalse(LegiscanVoteConverter.isProcedural(description), description);
			assertTrue(LegiscanVoteConverter.isBillVote(description), description);
		}
	}
}
