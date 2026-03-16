package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;
import us.poliscore.model.bill.BillTextPublishVersion;
import us.poliscore.model.bill.CongressionalBillType;

class BillServiceTest {

	@Test
	void stateBillsPreferNewestLastUpdatedOverLegiscanTypeOrdering() {
		Bill bill = new Bill();
		bill.setId("BIL/us/co/2025/hb/1001");
		
		BillText olderEnrolled = BillText.factory(bill.getId(), "older", LocalDate.of(2025, 2, 1), "ENROLLED-1", BillTextFormat.TEXT);
		BillText newerIntroduced = BillText.factory(bill.getId(), "newer", LocalDate.of(2025, 3, 1), "INTRODUCED-2", BillTextFormat.TEXT);
		
		List<BillText> sorted = List.of(olderEnrolled, newerIntroduced).stream()
				.sorted(new BillService().getBillTextComparator(bill))
				.toList();
		
		assertEquals(List.of(olderEnrolled.getId(), newerIntroduced.getId()), sorted.stream().map(BillText::getId).toList());
	}

	@Test
	void congressionalBillsStillPreferMaturePublishVersions() {
		String billId = Bill.generateId(LegislativeNamespace.US_CONGRESS, "118", CongressionalBillType.HR, 1);
		Bill bill = new Bill();
		bill.setId(billId);
		
		BillText introduced = BillText.factory(billId, "<bill>introduced</bill>", LocalDate.of(2024, 1, 1), BillTextPublishVersion.IH, BillTextFormat.XML);
		BillText enrolled = BillText.factory(billId, "<bill>enrolled</bill>", LocalDate.of(2024, 6, 1), BillTextPublishVersion.ENR, BillTextFormat.XML);
		
		List<BillText> sorted = List.of(enrolled, introduced).stream()
				.sorted(new BillService().getBillTextComparator(bill))
				.toList();
		
		assertEquals(List.of(introduced.getId(), enrolled.getId()), sorted.stream().map(BillText::getId).toList());
	}
}
