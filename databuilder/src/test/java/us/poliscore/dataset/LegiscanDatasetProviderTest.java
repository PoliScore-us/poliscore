package us.poliscore.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.legiscan.view.LegiscanAmendmentView;
import us.poliscore.legiscan.view.LegiscanBillTextView;
import us.poliscore.legiscan.view.LegiscanBillType;
import us.poliscore.legiscan.view.LegiscanBillView;
import us.poliscore.legiscan.view.LegiscanMimeType;
import us.poliscore.legiscan.view.LegiscanParty;
import us.poliscore.legiscan.view.LegiscanPeopleView;
import us.poliscore.legiscan.view.LegiscanProgressView;
import us.poliscore.legiscan.view.LegiscanRole;
import us.poliscore.legiscan.view.LegiscanStatus;
import us.poliscore.legiscan.view.LegiscanTextMetadataView;
import us.poliscore.legiscan.view.LegiscanTextType;
import us.poliscore.legiscan.view.LegiscanVoteView;
import us.poliscore.model.CongressionalSession;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillStatus;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;
import us.poliscore.model.legislator.Legislator;

class LegiscanDatasetProviderTest {

	@Test
	void buildBillTextVersionUsesGpoIdentityWhenStateLinkProvidesOne() {
		LegiscanTextMetadataView metadata = new LegiscanTextMetadataView();
		metadata.setDocId(12345);
		metadata.setTypeId(LegiscanTextType.AMENDED.getValue());
		metadata.setStateLink("https://www.govinfo.gov/content/pkg/BILLS-119hr1234ih2/xml/BILLS-119hr1234ih2.xml");

		assertEquals("IH2", new LegiscanDatasetProvider().buildBillTextVersion("BIL/us/congress/119/hr/1234", metadata));
	}

	@Test
	void buildBillTextVersionIgnoresGpoShapedStateLinkForStateBills() {
		LegiscanTextMetadataView metadata = new LegiscanTextMetadataView();
		metadata.setDocId(12345);
		metadata.setTypeId(LegiscanTextType.AMENDED.getValue());
		metadata.setStateLink("https://example.test/BILLS-119hr1234ih2.xml");

		assertEquals("AMENDED-12345", new LegiscanDatasetProvider().buildBillTextVersion("BIL/us/az/2026/hb/1234", metadata));
	}

	@Test
	void buildBillTextVersionFallsBackToTypeAndDocId() {
		LegiscanTextMetadataView metadata = new LegiscanTextMetadataView();
		metadata.setDocId(12345);
		metadata.setTypeId(LegiscanTextType.AMENDED.getValue());
		
		assertEquals("AMENDED-12345", new LegiscanDatasetProvider().buildBillTextVersion(metadata));
	}

	@Test
	void buildsVersionedBillTextFromLegacyTextAndUniqueDateMetadata() {
		Bill bill = new Bill();
		bill.setId("BIL/us/ga/2167/hb/1383");

		BillText legacy = BillText.factory(
				bill.getId(), null, "BE IT ENACTED", LocalDate.of(2026, 2, 19), BillTextFormat.TEXT);

		LegiscanTextMetadataView metadata = new LegiscanTextMetadataView();
		metadata.setDocId(3389123);
		metadata.setDate(LocalDate.of(2026, 2, 19));
		metadata.setTypeId(LegiscanTextType.INTRODUCED.getValue());
		metadata.setMimeId(LegiscanMimeType.PDF.getValue());

		BillText migrated = new LegiscanDatasetProvider()
				.buildMigratedLegacyBillText(bill, legacy, List.of(metadata))
				.orElseThrow();

		assertEquals("BTX/us/ga/2167/hb/1383/INTRODUCED-3389123", migrated.getId());
		assertEquals(Integer.valueOf(3389123), migrated.getLegiscanId());
		assertEquals("BE IT ENACTED", migrated.getDocument());
		assertEquals(LocalDate.of(2026, 2, 19), migrated.getLastUpdated());
		assertEquals(BillTextFormat.TEXT, migrated.getFormat());
	}

	@Test
	void doesNotGuessLegacyVersionWhenDateMetadataIsAmbiguous() {
		Bill bill = new Bill();
		bill.setId("BIL/us/ga/2167/hb/1383");
		BillText legacy = BillText.factory(
				bill.getId(), null, "BE IT ENACTED", LocalDate.of(2026, 2, 19), BillTextFormat.TEXT);

		LegiscanTextMetadataView first = textMetadata(LocalDate.of(2026, 2, 19));
		first.setDocId(1);
		LegiscanTextMetadataView second = textMetadata(LocalDate.of(2026, 2, 19));
		second.setDocId(2);

		assertTrue(new LegiscanDatasetProvider()
				.buildMigratedLegacyBillText(bill, legacy, List.of(first, second))
				.isEmpty());
	}

	@Test
	void extractBillTextDecodesHtmlUsingDeclaredCharset() {
		String html = """
				<html>
				<head><meta http-equiv=Content-Type content="text/html; charset=windows-1252"></head>
				<body><p>FTE positions\u00A0\u00A0\u00A027.0</p></body>
				</html>
				""";
		LegiscanBillTextView doc = new LegiscanBillTextView();
		doc.setMimeId(LegiscanMimeType.HTML.getValue());
		doc.setDoc(Base64.getEncoder().encodeToString(html.getBytes(Charset.forName("windows-1252"))));

		assertEquals(html, new LegiscanDatasetProvider().extractBillText(doc));
	}

	@Test
	void extractBillTextDecodesRtfUsingDeclaredCodePage() {
		String rtf = "{\\rtf1\\ansi\\ansicpg1252 Amount \\u8212? \\u8220?quoted\\u8221?\\par}";
		LegiscanBillTextView doc = new LegiscanBillTextView();
		doc.setMimeId(LegiscanMimeType.RICH_TEXT_FORMAT.getValue());
		doc.setDoc(Base64.getEncoder().encodeToString(rtf.getBytes(Charset.forName("windows-1252"))));

		assertEquals(rtf, new LegiscanDatasetProvider().extractBillText(doc));
	}

	@Test
	void extractBillTextRejectsFormatUnavailablePlaceholderNotice() {
		String html = """
				<html>
				<body>
				  <p>The HTML and Word versions of this bill are not available. Please
				  	see the PDF for the content of this bill.</p>
				  <p>For additional information, consult the Legislative Budget Board's
				  	website.</p>
				</body>
				</html>
				""";
		LegiscanBillTextView doc = new LegiscanBillTextView();
		doc.setMimeId(LegiscanMimeType.HTML.getValue());
		doc.setDoc(Base64.getEncoder().encodeToString(html.getBytes(Charset.forName("windows-1252"))));

		assertNull(new LegiscanDatasetProvider().extractBillText(doc));
	}

	@Test
	void placeholderDetectorRejectsEquivalentRedirectNotices() {
		LegiscanDatasetProvider provider = new LegiscanDatasetProvider();

		assertTrue(provider.isUnsupportedBillTextPlaceholder("""
				Text document unavailable.
				Please view the PDF document for the full text.
				"""));
		assertTrue(provider.isUnsupportedBillTextPlaceholder("""
				<div>The Word version cannot be displayed. Download the PDF for bill content.</div>
				"""));
	}

	@Test
	void placeholderDetectorKeepsShortSubstantiveBillText() {
		LegiscanDatasetProvider provider = new LegiscanDatasetProvider();

		assertFalse(provider.isUnsupportedBillTextPlaceholder("""
				BE IT ENACTED BY THE LEGISLATURE OF THE STATE OF TEXAS:
				SECTION 1. This Act may be cited as the Example Act.
				"""));
		assertFalse(provider.isUnsupportedBillTextPlaceholder("""
				WHEREAS, the House of Representatives honors the public service of Texans; now, therefore, be it
				RESOLVED, That the House congratulate the honoree.
				"""));
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

	@Test
	void importCongressionalLegislatorResolvesMissingBioguideFromUscData() {
		CongressionalSession congressionalSession = CongressionalSession.S119;
		LegislativeSession session = new LegislativeSession(true, congressionalSession.getStartDate(), congressionalSession.getEndDate(), "119", LegislativeNamespace.US_CONGRESS);
		PoliscoreDataset dataset = new PoliscoreDataset(session, new DeploymentConfig(LegislativeNamespace.US_CONGRESS, 2026));

		LegiscanPeopleView view = new LegiscanPeopleView();
		view.setPeopleId(26658);
		view.setName("Clay Fuller");
		view.setFirstName("Clay");
		view.setLastName("Fuller");
		view.setBioguideId("");
		view.setDistrict("US-GA-14");
		view.setPartyId(LegiscanParty.REPUBLICAN.getValue());
		view.setRoleId(LegiscanRole.REPRESENTATIVE.getValue());

		new LegiscanDatasetProvider().importLegislator(view, dataset);

		Legislator leg = dataset.get("LEG/us/congress/119/F000485", Legislator.class).orElseThrow();
		assertEquals(Integer.valueOf(26658), leg.getLegiscanId());
		assertEquals("Clay Fuller", leg.getName().getOfficial_full());
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
