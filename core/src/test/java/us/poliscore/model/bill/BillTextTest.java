package us.poliscore.model.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import us.poliscore.PoliscoreUtil;

class BillTextTest {

	@Test
	void xmlFactoryStoresXmlInTextFieldAndMarksFormat() {
		BillText billText = BillText.factory("BIL/us/congress/118/hr/1", "<bill/>", LocalDate.of(2024, 1, 1), BillTextPublishVersion.IH, BillTextFormat.XML);
		
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
		BillText billText = BillText.factory("BIL/us/congress/118/hr/1", "<bill/>", LocalDate.of(2024, 1, 1), BillTextPublishVersion.IH, BillTextFormat.XML);
		
		String json = PoliscoreUtil.getObjectMapper().writeValueAsString(billText);
		
		assertTrue(json.contains("\"lastUpdate\""));
		assertFalse(json.contains("\"lastUpdated\""));
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
