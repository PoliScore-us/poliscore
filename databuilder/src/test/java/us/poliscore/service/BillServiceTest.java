package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import us.poliscore.model.AISliceInterpretationMetadata;
import us.poliscore.model.IssueStats;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.BillStatus;
import us.poliscore.model.bill.StructuralAnalysis;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.model.legislator.LegislatorInterpretation;
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
	void displayedBillAndLegislatorUseReportedTextAfterIntroducedTextIsReanalyzed() throws Exception {
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

		var overall = TrackedIssue.OverallBenefitToSociety;
		reportedInterpretation.setIssueStats(new IssueStats());
		reportedInterpretation.getIssueStats().setStat(overall, -60);
		reportedInterpretation.setStructuralAnalysisPassFail(Map.of(StructuralAnalysis.EVIDENCE, true));
		introducedAlias.setIssueStats(new IssueStats());
		introducedAlias.getIssueStats().setStat(overall, 60);
		introducedAlias.setStructuralAnalysisPassFail(Map.of(StructuralAnalysis.EVIDENCE, false));
		bill.setName("Version selection test");
		bill.setOriginatingChamber(LegislativeChamber.UPPER);
		bill.setStatus(new BillStatus("Reported", 0.5f, "REPORTED"));
		bill.setTexts(List.of(introduced, reported));
		bill.setInterpretations(List.of(reportedInterpretation, introducedAlias));
		assertEquals(-60, bill.getRating());
		assertEquals(reportedInterpretation, service.getInterpretation(bill).orElseThrow());
		assertEquals(reportedInterpretation, service.getInterpretation(billId).orElseThrow());

		var scorer = new LegislatorInterpretationService();
		var billServiceField = LegislatorInterpretationService.class.getDeclaredField("billService");
		billServiceField.setAccessible(true);
		billServiceField.set(scorer, service);
		var dataField = LegislatorInterpretationService.class.getDeclaredField("data");
		dataField.setAccessible(true);
		dataField.set(scorer, new GovernmentDataService() {
			@Override public <T extends Persistable> Optional<T> get(String id, Class<T> type) {
				return billId.equals(id) ? Optional.of(type.cast(bill)) : Optional.empty();
			}
		});
		var sponsor = new LegislatorBillSponsor();
		sponsor.setBillId(billId);
		sponsor.setDate(LocalDate.of(2026, 1, 1));
		var legislator = new Legislator();
		legislator.setInteractions(List.of(sponsor));
		scorer.updateInteractionsInterp(legislator);
		var report = new LegislatorInterpretation();
		report.setIssueStats(scorer.calculateAgregateInteractionStats(legislator).toIssueStats());
		scorer.calculateImpactAndRating(legislator, report);
		assertEquals(-60, sponsor.getRating());
		assertEquals(-60, report.getIssueStats().getStat(overall));
		assertEquals(-60, report.getRating());
		assertEquals(1d, report.getStructuralStats().getStat(StructuralAnalysis.EVIDENCE));
	}

	@Test
	void sliceRequestsPreferTheirTextVersionAndStaySeparateFromWholeBillScores() {
		String billId = "BIL/us/congress/119/s/1579";
		var date = LocalDate.of(2026, 1, 1);
		var text = BillText.factory(billId, null, "reported", date, "RS",
				us.poliscore.model.bill.BillTextFormat.CONGRESS_BILL_XML);
		var wholeBill = interpretation(billId, "IS", date.atStartOfDay());
		var reportedSlice = interpretation(billId, "RS", date.plusDays(1).atStartOfDay());
		var introducedSlice = interpretation(billId, "IS", date.plusDays(2).atStartOfDay());
		for (var slice : List.of(reportedSlice, introducedSlice)) {
			var metadata = new AISliceInterpretationMetadata();
			metadata.setSliceIndex(1);
			slice.setMetadata(metadata);
			slice.setId(BillInterpretation.generateId(billId, slice.getSourceBillTextVersion(), 1));
		}
		var service = serviceWithData(List.of(text), List.of(wholeBill, reportedSlice, introducedSlice));
		assertEquals(reportedSlice, service.getInterpretation(billId, 1).orElseThrow());
		assertEquals(wholeBill, service.getInterpretation(billId).orElseThrow());
		assertEquals(Optional.empty(), service.getInterpretation(billId, 2));
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
			public List<BillText> getBillTexts(String billId) {
				return texts;
			}

			@Override
			public SortedSet<BillInterpretation> getBillInterpretations(String billId) {
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
			public List<BillText> getBillTexts(String billId) {
				return List.of();
			}

			@Override
			public SortedSet<BillInterpretation> getBillInterpretations(String billId) {
				return new TreeSet<>(Comparator.comparing(BillInterpretation::getId));
			}

			@Override
			public void populatePressInterps(BillInterpretation interpretation) {
				// This unit test exercises timestamp application without external storage.
			}
		};
	}
}
