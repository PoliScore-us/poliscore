package us.poliscore.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.poliscore.legiscan.view.LegiscanAmendmentView;
import us.poliscore.legiscan.view.LegiscanBillType;
import us.poliscore.legiscan.view.LegiscanBillView;
import us.poliscore.legiscan.view.LegiscanProgressView;
import us.poliscore.legiscan.view.LegiscanStatus;
import us.poliscore.legiscan.view.LegiscanTextMetadataView;
import us.poliscore.legiscan.view.LegiscanTextType;
import us.poliscore.legiscan.view.LegiscanVoteView;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.bill.BillStatus;

class LegiscanDatasetProviderTest {

	@Test
	void buildBillTextVersionUsesTypeAndDocId() {
		LegiscanTextMetadataView metadata = new LegiscanTextMetadataView();
		metadata.setDocId(12345);
		metadata.setTypeId(LegiscanTextType.AMENDED.getValue());
		
		assertEquals("AMENDED-12345", new LegiscanDatasetProvider().buildBillTextVersion(metadata));
	}

	@Test
	void resolvesFallbackBillMetadataWhenHistoryIsMissing() {
		LegiscanBillView view = new LegiscanBillView();
		view.setBillNumber("HB4039");
		view.setBody("H");
		view.setBillTypeId(LegiscanBillType.BILL.getValue());
		view.setTexts(List.of(textMetadata(LocalDate.of(2026, 2, 5))));
		view.setProgress(List.of(progress(LocalDate.of(2026, 2, 6))));
		view.setVotes(List.of(vote(LocalDate.of(2026, 2, 10))));
		view.setAmendments(List.of(amendment(LocalDate.of(2026, 2, 8))));

		LegiscanDatasetProvider provider = new LegiscanDatasetProvider();

		assertEquals(LegislativeChamber.LOWER, provider.resolveOriginatingChamber(view).orElseThrow());
		assertEquals(LocalDate.of(2026, 2, 5), provider.resolveIntroducedDate(view).orElseThrow());
		assertEquals(LocalDate.of(2026, 2, 10), provider.resolveLastActionDate(view).orElseThrow());
	}

	@Test
	void emptyHistoryAndNoSecondaryDataStillFailsFallbackResolution() {
		LegiscanBillView view = new LegiscanBillView();
		view.setBillNumber("X1234");

		LegiscanDatasetProvider provider = new LegiscanDatasetProvider();

		assertTrue(provider.resolveOriginatingChamber(view).isEmpty());
		assertTrue(provider.resolveIntroducedDate(view).isEmpty());
		assertTrue(provider.resolveLastActionDate(view).isEmpty());
		assertFalse(provider.collectBillDates(view).iterator().hasNext());
	}

	@Test
	void buildStatusFallsBackToDerivedChamberWhenHistoryIsMissing() {
		LegiscanBillView view = new LegiscanBillView();
		view.setBillNumber("HB4039");
		view.setBody("H");
		view.setStatusId(LegiscanStatus.INTRODUCED.getValue());
		view.setBillTypeId(LegiscanBillType.BILL.getValue());

		LegislativeSession session = new LegislativeSession(true, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "2243", LegislativeNamespace.US_COLORADO);
		BillStatus status = new LegiscanDatasetProvider().buildStatus(view, session);

		assertEquals("Introduced in the House", status.getDescription());
		assertEquals(0.0f, status.getProgress());
	}

	private LegiscanTextMetadataView textMetadata(LocalDate date) {
		LegiscanTextMetadataView text = new LegiscanTextMetadataView();
		text.setDate(date);
		return text;
	}

	private LegiscanProgressView progress(LocalDate date) {
		LegiscanProgressView progress = new LegiscanProgressView();
		progress.setDate(date);
		return progress;
	}

	private LegiscanVoteView vote(LocalDate date) {
		LegiscanVoteView vote = new LegiscanVoteView();
		vote.setDate(date);
		return vote;
	}

	private LegiscanAmendmentView amendment(LocalDate date) {
		LegiscanAmendmentView amendment = new LegiscanAmendmentView();
		amendment.setDate(date);
		return amendment;
	}
}
