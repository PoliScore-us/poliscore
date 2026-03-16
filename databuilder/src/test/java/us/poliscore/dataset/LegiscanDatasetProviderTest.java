package us.poliscore.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import us.poliscore.legiscan.view.LegiscanTextMetadataView;
import us.poliscore.legiscan.view.LegiscanTextType;

class LegiscanDatasetProviderTest {

	@Test
	void buildBillTextVersionUsesTypeAndDocId() {
		LegiscanTextMetadataView metadata = new LegiscanTextMetadataView();
		metadata.setDocId(12345);
		metadata.setTypeId(LegiscanTextType.AMENDED.getValue());
		
		assertEquals("AMENDED-12345", new LegiscanDatasetProvider().buildBillTextVersion(metadata));
	}
}
