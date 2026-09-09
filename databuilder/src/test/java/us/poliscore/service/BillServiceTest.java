package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillText;

class BillServiceTest {

	@Test
	void applyInterpretationAcceptsLegacyInterpretationWithoutTimestamp() {
		Bill bill = new Bill();
		bill.setId("BIL/us/congress/119/hr/1");
		bill.setLastActionDate(LocalDate.of(2026, 2, 3));
		BillInterpretation interpretation = new BillInterpretation();
		interpretation.setId("BIT/us/congress/119/hr/1/IH");
		interpretation.setBillId(bill.getId());
		interpretation.setSourceBillTextVersion("IH");

		assertDoesNotThrow(() -> serviceWithoutStorageData().applyInterpretation(bill, interpretation));
		assertEquals(LocalDateTime.of(2026, 2, 3, 0, 0), bill.getLastUpdate());
	}

	@Test
	void applyInterpretationPreservesExistingBillTimestampWhenInterpretationHasNoTimestamp() {
		Bill bill = new Bill();
		bill.setId("BIL/us/congress/119/hr/1");
		bill.setLastUpdate(LocalDateTime.of(2026, 4, 5, 6, 7));
		BillInterpretation interpretation = new BillInterpretation();
		interpretation.setId("BIT/us/congress/119/hr/1/IH");
		interpretation.setBillId(bill.getId());

		serviceWithoutStorageData().applyInterpretation(bill, interpretation);

		assertEquals(LocalDateTime.of(2026, 4, 5, 6, 7), bill.getLastUpdate());
	}

	@Test
	void selectsInterpretationForLatestTextInsteadOfMostRecentlyWrittenInterpretation() {
		String billId = "BIL/us/congress/119/s/1579";
		Bill bill = new Bill();
		bill.setId(billId);
		BillText introduced = BillText.factory(
				billId, null, "same", LocalDate.of(2026, 2, 1), "IS", us.poliscore.model.bill.BillTextFormat.CONGRESS_BILL_XML);
		BillText reported = BillText.factory(
				billId, null, "same", LocalDate.of(2026, 1, 1), "RS", us.poliscore.model.bill.BillTextFormat.CONGRESS_BILL_XML);
		BillInterpretation reportedInterpretation = interpretation(billId, "RS", LocalDateTime.of(2026, 1, 2, 0, 0));
		BillInterpretation introducedAlias = interpretation(billId, "IS", LocalDateTime.of(2026, 2, 2, 0, 0));
		BillService service = serviceWithData(
				List.of(introduced, reported),
				List.of(reportedInterpretation, introducedAlias));

		assertEquals(reportedInterpretation, service.getInterpretation(bill).orElseThrow());
	}

	@Test
	void fallsBackToNewestInterpretationWhenLatestTextHasNoInterpretation() {
		String billId = "BIL/us/congress/119/s/1579";
		Bill bill = new Bill();
		bill.setId(billId);
		BillText reported = BillText.factory(
				billId, null, "changed", LocalDate.of(2026, 1, 1), "RS", us.poliscore.model.bill.BillTextFormat.CONGRESS_BILL_XML);
		BillInterpretation introduced = interpretation(billId, "IS", LocalDateTime.of(2026, 1, 2, 0, 0));
		BillService service = serviceWithData(List.of(reported), List.of(introduced));

		assertEquals(introduced, service.getInterpretation(bill).orElseThrow());
	}

	private BillInterpretation interpretation(String billId, String version, LocalDateTime lastUpdate) {
		BillInterpretation interpretation = new BillInterpretation();
		interpretation.setId(BillInterpretation.generateId(billId, version, null));
		interpretation.setBillId(billId);
		interpretation.setSourceBillTextVersion(version);
		interpretation.setLastUpdate(lastUpdate);
		return interpretation;
	}

	private BillService serviceWithData(List<BillText> texts, List<BillInterpretation> interpretations) {
		return new BillService() {
			@Override
			public List<BillText> getBillTexts(Bill bill) {
				return texts;
			}

			@Override
			public SortedSet<BillInterpretation> getBillInterpretations(Bill bill) {
				TreeSet<BillInterpretation> sorted = new TreeSet<>(Comparator
						.comparing(BillInterpretation::getLastUpdate)
						.thenComparing(BillInterpretation::getId));
				sorted.addAll(interpretations);
				return sorted;
			}
		};
	}

	private BillService serviceWithoutStorageData() {
		return new BillService() {
			@Override
			public List<BillText> getBillTexts(Bill bill) {
				return List.of();
			}

			@Override
			public SortedSet<BillInterpretation> getBillInterpretations(Bill bill) {
				return new TreeSet<>(Comparator.comparing(BillInterpretation::getId));
			}

			@Override
			public void populatePressInterps(BillInterpretation interpretation) {
				// This unit test exercises timestamp application without external storage.
			}
		};
	}
}
