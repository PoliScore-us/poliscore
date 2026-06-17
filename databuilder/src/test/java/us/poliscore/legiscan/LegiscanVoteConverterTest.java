package us.poliscore.legiscan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
