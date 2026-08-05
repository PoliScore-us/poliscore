package us.poliscore.dataset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.view.USCLegislatorView;
import us.poliscore.view.USCLegislatorView.USCLegislativeTerm;

class USCDatasetProviderTest {

	@Test
	void excludesHouseMemberWhoseTermEndsAtStartOfCongress() {
		USCLegislatorView view = legislatorWithTerm(LocalDate.of(2023, 1, 3), LocalDate.of(2025, 1, 3));
		LegislativeSession session119 = congressionalSession("119");

		assertFalse(USCDatasetProvider.isMemberOfCongressionalSession(view, session119));
	}

	@Test
	void includesHouseMemberWhoseTermStartsAtStartOfCongress() {
		USCLegislatorView view = legislatorWithTerm(LocalDate.of(2025, 1, 3), LocalDate.of(2027, 1, 3));
		LegislativeSession session119 = congressionalSession("119");

		assertTrue(USCDatasetProvider.isMemberOfCongressionalSession(view, session119));
	}

	@Test
	void includesSenatorWhoseTermSpansCongress() {
		USCLegislatorView view = legislatorWithTerm(LocalDate.of(2021, 1, 3), LocalDate.of(2027, 1, 3));
		LegislativeSession session119 = congressionalSession("119");

		assertTrue(USCDatasetProvider.isMemberOfCongressionalSession(view, session119));
	}

	private static USCLegislatorView legislatorWithTerm(LocalDate start, LocalDate end) {
		USCLegislativeTerm term = new USCLegislativeTerm();
		term.setStart(start);
		term.setEnd(end);

		USCLegislatorView view = new USCLegislatorView();
		view.setTerms(List.of(term));
		return view;
	}

	private static LegislativeSession congressionalSession(String code) {
		return new LegislativeSession(
				true,
				LocalDate.of(2025, 1, 1),
				LocalDate.of(2026, 12, 31),
				code,
				LegislativeNamespace.US_CONGRESS);
	}
}
