package us.poliscore.model.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import us.poliscore.model.AIInterpretationMetadata;

class BillInterpretationParserTest {

	@Test
	void parsesMultilineAndFencedTopicJsonArrays() {
		Bill bill = new Bill();
		bill.setId("BIL/us/congress/119/hr/1");
		bill.setName("Test Bill");
		bill.setOfficialUrl("https://example.com/bill");

		BillInterpretation interp = new BillInterpretation();
		interp.setBill(bill);
		interp.setId(BillInterpretation.generateId(bill.getId(), "IH", null));
		interp.setMetadata(AIInterpretationMetadata.construct("test", "test", 1, false));
		interp.getMetadata().setDate(LocalDate.of(2026, 1, 1));

		new BillInterpretationParser(bill, interp, null).parse("""
				Neutral Summary:
				This bill is a small test bill.

				Bill Title:
				Test Bill

				Other Names:
				```json
				[
				  "Test Act",
				  "Example Act"
				]
				```

				Topics:
				[
				  "gun violence",
				  "firearm regulation"
				]

				Structural Analysis:
				1. Precision: The test analysis has enough ordinary prose to pass validation. <PASS>
				2. Evidence: The test analysis has enough ordinary prose to pass validation. <PASS>
				3. Feasibility: The test analysis has enough ordinary prose to pass validation. <PASS>
				4. Budget: The test analysis has enough ordinary prose to pass validation. <PASS>
				5. Fairness: The test analysis has enough ordinary prose to pass validation. <PASS>
				6. Governance: The test analysis has enough ordinary prose to pass validation. <PASS>
				7. Risk: The test analysis has enough ordinary prose to pass validation. <PASS>

				Impact Analysis:
				This bill has a narrow test impact.

				Long Report:
				This is a plain long report for the parser test.

				Confidence:
				90

				Impact Stats:
				Overall Benefit to Society: 10

				Rating:
				10

				Casual Report:
				This is a plain casual report for the parser test.

				Short Report:
				This is a plain short report for the parser test.
				""", null);

		assertEquals(java.util.List.of("Test Act", "Example Act"), interp.getOtherNames());
		assertEquals(java.util.List.of("gun violence", "firearm regulation"), interp.getTopics());
	}
}
