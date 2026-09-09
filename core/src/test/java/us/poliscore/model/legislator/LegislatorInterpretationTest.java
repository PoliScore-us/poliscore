package us.poliscore.model.legislator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import us.poliscore.model.IssueStats;
import us.poliscore.model.TrackedIssue;

class LegislatorInterpretationTest {

	@Test
	void validatesNewInterpretationWithoutLastUpdate() {
		LegislatorInterpretation interpretation = new LegislatorInterpretation();
		interpretation.setLongExplain("A valid long explanation.");
		interpretation.setCasualExplain("A valid casual explanation.");
		interpretation.setShortExplain("A valid short explanation.");

		IssueStats stats = new IssueStats();
		stats.setStat(TrackedIssue.OverallBenefitToSociety, 1);
		interpretation.setIssueStats(stats);

		assertDoesNotThrow(interpretation::validate);
	}

	@Test
	void parsesMarkdownFormattedSectionHeaders() {
		LegislatorInterpretation interpretation = new LegislatorInterpretation();
		IssueStats stats = new IssueStats();
		stats.setStat(TrackedIssue.OverallBenefitToSociety, 1);
		interpretation.setIssueStats(stats);

		new LegislatorInterpretationParser(interpretation).parse("""
				## Reasoning Steps:
				Reasoning content.

				## **Long Report**:
				Long report content.

				### **Casual Report:**
				Casual report content.

				**Short Report:**
				Short report content.

				## Media References:
				[]
				""");

		assertEquals("Reasoning content.", interpretation.getReasoning().strip());
		assertEquals("Long report content.", interpretation.getLongExplain());
		assertEquals("Casual report content.", interpretation.getCasualExplain());
		assertEquals("Short report content.", interpretation.getShortExplain());
	}
}
