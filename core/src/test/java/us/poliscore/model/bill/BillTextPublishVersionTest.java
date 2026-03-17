package us.poliscore.model.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BillTextPublishVersionTest {

	@Test
	void parseFromBillTextNameSupportsStageCountSuffix() {
		assertEquals(BillTextPublishVersion.RH, BillTextPublishVersion.parseFromBillTextName("BILLS-118hr7643rh2.xml"));
		assertEquals(BillTextPublishVersion.RFS, BillTextPublishVersion.parseFromBillTextName("BILLS-119hr3426rfs2.xml"));
	}

	@Test
	void parseFromBillTextNameSupportsStarPrintSuffix() {
		assertEquals(BillTextPublishVersion.EH, BillTextPublishVersion.parseFromBillTextName("BILLS-119hr3426eh1s.xml"));
	}
}
