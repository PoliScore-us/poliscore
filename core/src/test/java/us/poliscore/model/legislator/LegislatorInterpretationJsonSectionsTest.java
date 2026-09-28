package us.poliscore.model.legislator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import us.poliscore.model.IssueStats;
import us.poliscore.model.TrackedIssue;

class LegislatorInterpretationJsonSectionsTest {

	@Test
	void parsesConstituencyAndCampaignFinanceAsCompactJson() {
		LegislatorInterpretation interpretation = new LegislatorInterpretation();
		IssueStats stats = new IssueStats();
		stats.setStat(TrackedIssue.OverallBenefitToSociety, 1);
		interpretation.setIssueStats(stats);

		new LegislatorInterpretationParser(interpretation).parse("""
				Long Report:
				A valid long explanation.

				Casual Report:
				A valid casual explanation.

				Short Report:
				Focuses on testing.

				## Constituency:
				```json
				{
				  "density": "SUBURB",
				  "satisfaction": 18
				}
				```

				**Campaign Finance:** { "confidence": 85, "topContributors": [] }

				Media References:
				[]
				""");

		assertEquals("{\"density\":\"SUBURB\",\"satisfaction\":18}", interpretation.getConstituency());
		assertEquals("{\"confidence\":85,\"topContributors\":[]}", interpretation.getCampaignFinance());
	}
}
