package us.poliscore.model.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.poliscore.PoliscoreUtil;

class BillTextTest {

	@Test
	void xmlFactoryStoresXmlInTextFieldAndMarksFormat() {
		BillText billText = BillText.factory("BIL/us/congress/118/hr/1", 0, "<bill/>", LocalDate.of(2024, 1, 1), BillTextPublishVersion.IH, BillTextFormat.XML);
		
		assertEquals("<bill/>", billText.getText());
		assertEquals(BillTextFormat.XML, billText.getFormat());
		assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0), billText.getLastUpdate());
	}

	@Test
	void legacyXmlJsonStillDeserializesWithoutPatching() throws Exception {
		String json = """
				{
				  "id":"BTX/us/congress/118/hr/1",
				  "billId":"BIL/us/congress/118/hr/1",
				  "xml":"<bill/>",
				  "lastUpdated":"2024-01-01"
				}
				""";
		
		BillText billText = PoliscoreUtil.getObjectMapper().readValue(json, BillText.class);
		
		assertEquals("<bill/>", billText.getText());
		assertEquals(BillTextFormat.XML, billText.getFormat());
		assertEquals(LocalDate.of(2024, 1, 1), billText.getLastUpdated());
		assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0), billText.getLastUpdate());
	}

	@Test
	void billTextSerializesOnlyCanonicalLastUpdateField() throws Exception {
		BillText billText = BillText.factory("BIL/us/congress/118/hr/1", 0, "<bill/>", LocalDate.of(2024, 1, 1), BillTextPublishVersion.IH, BillTextFormat.XML);
		
		String json = PoliscoreUtil.getObjectMapper().writeValueAsString(billText);
		
		assertTrue(json.contains("\"lastUpdate\""));
		assertFalse(json.contains("\"lastUpdated\""));
	}

	@Test
	void congressTextOrderingUsesLegislativeMaturityBeforePublicationDate() {
		String billId = "BIL/us/congress/119/s/1579";
		BillText reported = BillText.factory(
				billId, null, "reported", LocalDate.of(2026, 1, 1), "RS", BillTextFormat.CONGRESS_BILL_XML);
		BillText introduced = BillText.factory(
				billId, null, "introduced", LocalDate.of(2026, 2, 1), "IS", BillTextFormat.CONGRESS_BILL_XML);

		assertEquals(List.of(introduced, reported), List.of(reported, introduced).stream()
				.sorted(BillTextOrder.ASCENDING)
				.toList());
	}

	@Test
	void congressTextOrderingPrefersCanonicalVersionForEquivalentProviderStage() {
		String billId = "BIL/us/congress/119/hr/123";
		BillText provider = BillText.factory(
				billId, 456, "same", LocalDate.of(2026, 2, 1), "INTRODUCED-456", BillTextFormat.HTML);
		BillText canonical = BillText.factory(
				billId, null, "same", LocalDate.of(2026, 1, 1), "IH", BillTextFormat.CONGRESS_BILL_XML);

		assertTrue(BillTextOrder.ASCENDING.compare(provider, canonical) < 0);
	}

	@Test
	void billTextOrderingDoesNotCollapseDifferentVersionsPublishedOnSameDate() {
		String billId = "BIL/us/co/2026b/hb/1";
		BillText introduced = BillText.factory(
				billId, 1, "introduced", LocalDate.of(2026, 1, 1), "INTRODUCED-1", BillTextFormat.HTML);
		BillText engrossed = BillText.factory(
				billId, 2, "engrossed", LocalDate.of(2026, 1, 1), "ENGROSSED-2", BillTextFormat.HTML);
		Bill bill = new Bill();

		bill.setTexts(List.of(introduced, engrossed));

		assertEquals(2, bill.getTexts().size());
	}

	@Test
	void congressTextOrderingRemainsTransitiveWithUnknownProviderVersions() {
		String billId = "BIL/us/congress/119/s/123";
		BillText introduced = BillText.factory(
				billId, null, "introduced", LocalDate.of(2026, 3, 1), "IS", BillTextFormat.CONGRESS_BILL_XML);
		BillText unknown = BillText.factory(
				billId, 456, "unknown", LocalDate.of(2026, 2, 1), "AMENDED-456", BillTextFormat.HTML);
		BillText reported = BillText.factory(
				billId, null, "reported", LocalDate.of(2026, 1, 1), "RS", BillTextFormat.CONGRESS_BILL_XML);

		assertTrue(BillTextOrder.ASCENDING.compare(unknown, introduced) < 0);
		assertTrue(BillTextOrder.ASCENDING.compare(introduced, reported) < 0);
		assertTrue(BillTextOrder.ASCENDING.compare(unknown, reported) < 0);
	}

	@Test
	void billPrefersInterpretationForSelectedLatestTextOverNewerOlderVersionAlias() {
		String billId = "BIL/us/congress/119/s/1579";
		BillText introduced = BillText.factory(
				billId, null, "same", LocalDate.of(2026, 2, 1), "IS", BillTextFormat.CONGRESS_BILL_XML);
		BillText reported = BillText.factory(
				billId, null, "same", LocalDate.of(2026, 1, 1), "RS", BillTextFormat.CONGRESS_BILL_XML);
		BillInterpretation reportedInterpretation = interpretation(billId, "RS", LocalDateTime.of(2026, 1, 2, 0, 0));
		BillInterpretation introducedAlias = interpretation(billId, "IS", LocalDateTime.of(2026, 2, 2, 0, 0));
		Bill bill = new Bill();
		bill.setTexts(List.of(reported, introduced));
		bill.setInterpretations(List.of(reportedInterpretation, introducedAlias));

		assertEquals(reportedInterpretation, bill.getInterpretation());
	}

	private BillInterpretation interpretation(String billId, String version, LocalDateTime lastUpdate) {
		BillInterpretation interpretation = new BillInterpretation();
		interpretation.setId(BillInterpretation.generateId(billId, version, null));
		interpretation.setBillId(billId);
		interpretation.setSourceBillTextVersion(version);
		interpretation.setLastUpdate(lastUpdate);
		return interpretation;
	}

	@Test
	void legacyXmlWithMissingStoredDatesDerivesTimestampFromDocument() throws Exception {
		String json = """
				{
				  "id":"BTX/us/congress/119/hjres/35",
				  "billId":"BIL/us/congress/119/hjres/35",
				  "xml":"<resolution><metadata xmlns:dc=\\"http://purl.org/dc/elements/1.1/\\"><dublinCore><dc:date>2025-02-04</dc:date></dublinCore></metadata></resolution>",
				  "lastUpdated":null
				}
				""";
		
		BillText billText = PoliscoreUtil.getObjectMapper().readValue(json, BillText.class);
		
		assertEquals(LocalDate.of(2025, 2, 4), billText.getLastUpdated());
		assertEquals(LocalDateTime.of(2025, 2, 4, 0, 0), billText.getLastUpdate());
	}

	@Test
	void legacyXmlCanDeriveTimestampFromAttestationDate() throws Exception {
		String json = """
				{
				  "id":"BTX/us/congress/119/hr/3426/EH",
				  "billId":"BIL/us/congress/119/hr/3426",
				  "xml":"<bill><metadata xmlns:dc=\\"http://purl.org/dc/elements/1.1/\\"><dublinCore><dc:date></dc:date></dublinCore></metadata><attestation><attestation-group><attestation-date date=\\"20250915\\">Passed the House</attestation-date></attestation-group></attestation></bill>"
				}
				""";
		
		BillText billText = PoliscoreUtil.getObjectMapper().readValue(json, BillText.class);
		
		assertEquals(LocalDate.of(2025, 9, 15), billText.getLastUpdated());
		assertEquals(LocalDateTime.of(2025, 9, 15, 0, 0), billText.getLastUpdate());
	}
}
