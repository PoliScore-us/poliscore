package us.poliscore.bill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;

class BillTextMaterialityDetectorTest {

	@Test
	void ignoresCongressPublicationAndStatusWrapperChanges() {
		BillText introduced = text("IH", """
				<bill bill-stage="Introduced-in-House">
				  <metadata><date>2026-01-01</date></metadata>
				  <form><legis-type>A BILL</legis-type></form>
				  <legis-body><toc>Old generated contents</toc><section><text>The agency shall publish the report.</text></section></legis-body>
				</bill>
				""", BillTextFormat.CONGRESS_BILL_XML);
		BillText engrossed = text("EH", """
				<bill bill-stage="Engrossed-in-House">
				  <metadata><date>2026-02-02</date></metadata>
				  <form><legis-type>AN ACT</legis-type></form>
				  <legis-body id="new-id"><toc>Rebuilt generated contents</toc><section id="another-id"><text>The agency shall publish the report.</text></section></legis-body>
				  <attestation>Passed the House February 2, 2026.</attestation>
				</bill>
				""", BillTextFormat.CONGRESS_BILL_XML);

		assertTrue(BillTextMaterialityDetector.hasSameSubstantiveText(introduced, engrossed));
	}

	@Test
	void treatsEvenSmallLegislativeLanguageChangesAsMaterial() {
		BillText previous = text("IH", "<bill><legis-body>The agency shall publish the report.</legis-body></bill>", BillTextFormat.XML);
		BillText latest = text("EH", "<bill><legis-body>The agency shall not publish the report.</legis-body></bill>", BillTextFormat.XML);

		assertFalse(BillTextMaterialityDetector.hasSameSubstantiveText(previous, latest));
	}

	@Test
	void normalizesWhitespaceWithoutUsingAChangePercentageThreshold() {
		BillText previous = text("INTRODUCED-1", "The agency shall publish\n\tthe report.", BillTextFormat.TEXT);
		BillText latest = text("ENGROSSED-2", "  The agency shall publish the report.  ", BillTextFormat.TEXT);

		assertTrue(BillTextMaterialityDetector.hasSameSubstantiveText(previous, latest));
	}

	@Test
	void ignoresRecognizedPublicationLinesInExtractedDocuments() {
		BillText previous = text("INTRODUCED-1", """
				Document version: Introduced
				The agency shall publish the report.
				Page 1
				""", BillTextFormat.TEXT);
		BillText latest = text("ENGROSSED-2", """
				Document version: Engrossed
				The agency shall publish the report.
				Page 2
				""", BillTextFormat.TEXT);

		assertTrue(BillTextMaterialityDetector.hasSameSubstantiveText(previous, latest));
	}

	@Test
	void changingAnAgencyNameRemainsMaterial() {
		BillText previous = text("IH", "The Department shall publish the report.", BillTextFormat.TEXT);
		BillText latest = text("EH", "The Commission shall publish the report.", BillTextFormat.TEXT);

		assertFalse(BillTextMaterialityDetector.hasSameSubstantiveText(previous, latest));
	}

	@Test
	void punctuationChangesRemainMaterial() {
		BillText previous = text("IH", "The agency shall publish the report.", BillTextFormat.TEXT);
		BillText latest = text("EH", "The agency shall publish the report?", BillTextFormat.TEXT);

		assertFalse(BillTextMaterialityDetector.hasSameSubstantiveText(previous, latest));
	}

	@Test
	void capitalizationChangesRemainMaterial() {
		BillText previous = text("IH", "The Agency shall publish the report.", BillTextFormat.TEXT);
		BillText latest = text("EH", "The agency shall publish the report.", BillTextFormat.TEXT);

		assertFalse(BillTextMaterialityDetector.hasSameSubstantiveText(previous, latest));
	}

	@Test
	void editorialDraftingWordChangesRemainMaterial() {
		BillText previous = text("IH", "The agency shall publish the report herein.", BillTextFormat.TEXT);
		BillText latest = text("EH", "The agency shall publish the report.", BillTextFormat.TEXT);

		assertFalse(BillTextMaterialityDetector.hasSameSubstantiveText(previous, latest));
	}

	@Test
	void blankDocumentsNeverCountAsEquivalent() {
		assertFalse(BillTextMaterialityDetector.hasSameSubstantiveText(
				text("IH", "", BillTextFormat.TEXT), text("EH", "", BillTextFormat.TEXT)));
	}

	private BillText text(String version, String document, BillTextFormat format) {
		return BillText.factory("BIL/us/congress/119/hr/1", null, document, LocalDate.of(2026, 1, 1), version, format);
	}
}
