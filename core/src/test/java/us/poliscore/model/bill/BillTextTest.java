package us.poliscore.model.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import us.poliscore.PoliscoreUtil;

class BillTextTest {

	@Test
	void xmlFactoryStoresXmlInTextFieldAndMarksFormat() {
		BillText billText = BillText.factory("BIL/us/congress/118/hr/1", "<bill/>", LocalDate.of(2024, 1, 1), BillTextPublishVersion.IH, BillTextFormat.XML);
		
		assertEquals("<bill/>", billText.getText());
		assertEquals(BillTextFormat.XML, billText.getFormat());
	}

	@Test
	void legacyXmlSetterBackfillsTextAndFormat() {
		BillText billText = new BillText();
		billText.setXml("<bill/>");
		
		assertEquals("<bill/>", billText.getText());
		assertEquals(BillTextFormat.XML, billText.getFormat());
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
	}
}
