package us.poliscore.entrypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;
import us.poliscore.model.bill.BillTextIdentity;

class GPOBulkBillTextFetcherTest {

	private final GPOBulkBillTextFetcher fetcher = new GPOBulkBillTextFetcher();

	@Test
	void parseDateReadsBillDublinCoreDate() throws Exception {
		String xml = """
				<?xml version="1.0"?>
				<bill>
				  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
				    <dublinCore>
				      <dc:date>2025-04-09</dc:date>
				    </dublinCore>
				  </metadata>
				</bill>
				""";

		assertEquals(LocalDate.of(2025, 4, 9), GPOBulkBillTextFetcher.parseDate(xml));
	}

	@Test
	void parseDateReadsResolutionDublinCoreDate() throws Exception {
		String xml = """
				<?xml version="1.0"?>
				<resolution>
				  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
				    <dublinCore>
				      <dc:date>2025-02-04</dc:date>
				    </dublinCore>
				  </metadata>
				</resolution>
				""";

		assertEquals(LocalDate.of(2025, 2, 4), GPOBulkBillTextFetcher.parseDate(xml));
	}

	@Test
	void parseDateReadsAmendmentDublinCoreDate() throws Exception {
		String xml = """
				<?xml version="1.0"?>
				<amendment-doc>
				  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
				    <dublinCore>
				      <dc:date>2026-02-11</dc:date>
				    </dublinCore>
				  </metadata>
				</amendment-doc>
				""";

		assertEquals(LocalDate.of(2026, 2, 11), GPOBulkBillTextFetcher.parseDate(xml));
	}

	@Test
	void parseDateFallsBackToActionDateWhenDublinCoreDateMissing() throws Exception {
		String xml = """
				<?xml version="1.0"?>
				<amendment-doc>
				  <action>
				    <action-date date="20260211">February 11, 2026</action-date>
				  </action>
				</amendment-doc>
				""";

		assertEquals(LocalDate.of(2026, 2, 11), GPOBulkBillTextFetcher.parseDate(xml));
	}
	
	@Test
	void parseDateFallsBackToAttestationDateWhenOtherDatesMissing() throws Exception {
		String xml = """
				<?xml version="1.0"?>
				<bill>
				  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
				    <dublinCore>
				      <dc:date></dc:date>
				    </dublinCore>
				  </metadata>
				  <attestation>
				    <attestation-group>
				      <attestation-date date="20250915">Passed the House of Representatives September 15, 2025.</attestation-date>
				    </attestation-group>
				  </attestation>
				</bill>
				""";

		assertEquals(LocalDate.of(2025, 9, 15), GPOBulkBillTextFetcher.parseDate(xml));
	}

	@Test
	void extractBillNumberIgnoresVersionSuffixDigits() {
		assertEquals(3426, fetcher.extractBillNumber("119", "hr", "BILLS-119hr3426eh1s.xml"));
		assertEquals(7643, fetcher.extractBillNumber("118", "hr", "BILLS-118hr7643rh2.xml"));
		assertEquals(3426, fetcher.extractBillNumber("119", "hr", "BILLS-119hr3426rfs2.xml"));
	}

	@Test
	void selectCanonicalMigrationTargetPrefersMatchingVersionDate() {
		String billId = "BIL/us/congress/119/hr/123";
		BillText oldProviderText = BillText.factory(billId, 12345, "old", LocalDate.of(2026, 2, 1), "AMENDED-12345", BillTextFormat.TEXT);
		BillText introduced = BillText.factory(billId, null, "introduced", LocalDate.of(2026, 1, 1), "IH", BillTextFormat.CONGRESS_BILL_XML);
		BillText reported = BillText.factory(billId, null, "reported", LocalDate.of(2026, 2, 1), "RH", BillTextFormat.CONGRESS_BILL_XML);

		assertEquals("RH", fetcher.selectCanonicalMigrationTarget(oldProviderText, List.of(introduced, reported)).orElseThrow().getVersion());
	}

	@Test
	void selectCanonicalMigrationTargetConvertsIntroducedLegiscanVersion() {
		String billId = "BIL/us/congress/119/hr/123";
		BillText oldProviderText = BillText.factory(billId, 12345, "old", LocalDate.of(2026, 3, 1), "INTRODUCED-12345", BillTextFormat.TEXT);
		BillText introduced = BillText.factory(billId, null, "introduced", LocalDate.of(2026, 1, 1), "IH", BillTextFormat.CONGRESS_BILL_XML);
		BillText reported = BillText.factory(billId, null, "reported", LocalDate.of(2026, 2, 1), "RH", BillTextFormat.CONGRESS_BILL_XML);

		assertEquals("IH", fetcher.selectCanonicalMigrationTarget(oldProviderText, List.of(introduced, reported)).orElseThrow().getVersion());
	}

	@Test
	void selectCanonicalMigrationTargetPreservesGpoRevisionSuffix() {
		String billId = "BIL/us/congress/119/hr/123";
		BillText oldProviderText = BillText.factory(billId, null, "old", LocalDate.of(2026, 3, 1), "BILLS-119hr123rh2.xml", BillTextFormat.TEXT);
		BillText reported = BillText.factory(billId, null, "reported", LocalDate.of(2026, 2, 1), "RH2", BillTextFormat.CONGRESS_BILL_XML);

		assertEquals("RH2", fetcher.selectCanonicalMigrationTarget(oldProviderText, List.of(reported)).orElseThrow().getVersion());
	}

	@Test
	void selectCanonicalMigrationTargetConvertsSenateIntroducedLegiscanVersion() {
		String billId = "BIL/us/congress/119/s/123";
		BillText oldProviderText = BillText.factory(billId, 12345, "old", LocalDate.of(2026, 3, 1), "INTRODUCED-12345", BillTextFormat.TEXT);
		BillText introduced = BillText.factory(billId, null, "introduced", LocalDate.of(2026, 1, 1), "IS", BillTextFormat.CONGRESS_BILL_XML);
		BillText reported = BillText.factory(billId, null, "reported", LocalDate.of(2026, 2, 1), "RS", BillTextFormat.CONGRESS_BILL_XML);

		assertEquals("IS", fetcher.selectCanonicalMigrationTarget(oldProviderText, List.of(introduced, reported)).orElseThrow().getVersion());
	}

	@Test
	void selectCanonicalMigrationTargetTreatsOldUscGpoCodesAsCanonical() {
		String billId = "BIL/us/congress/119/hr/123";
		BillText oldUscText = BillText.factory(billId, null, "old", LocalDate.of(2026, 1, 1), "IH", BillTextFormat.XML);
		BillText introduced = BillText.factory(billId, null, "introduced", LocalDate.of(2026, 1, 1), "IH", BillTextFormat.CONGRESS_BILL_XML);

		assertEquals("IH", BillTextIdentity.canonicalCongressVersionFromStoredVersion(oldUscText.getVersion(), billId).orElseThrow());
		assertEquals(introduced.getVersion(), BillTextIdentity.canonicalCongressVersionFromStoredVersion(oldUscText.getVersion(), billId).orElseThrow());
	}

	@Test
	void selectCanonicalMigrationTargetMatchesNormalizedTextBeforeDateFallback() {
		String billId = "BIL/us/congress/119/hr/123";
		BillText oldProviderText = BillText.factory(billId, 12345, "old    text", LocalDate.of(2026, 3, 1), "AMENDED-12345", BillTextFormat.TEXT);
		BillText introduced = BillText.factory(billId, null, "introduced", LocalDate.of(2026, 1, 1), "IH", BillTextFormat.CONGRESS_BILL_XML);
		BillText reported = BillText.factory(billId, null, "old text", LocalDate.of(2026, 2, 1), "RH", BillTextFormat.CONGRESS_BILL_XML);

		assertEquals("RH", fetcher.selectCanonicalMigrationTarget(oldProviderText, List.of(introduced, reported)).orElseThrow().getVersion());
	}

	@Test
	void selectCanonicalMigrationTargetDoesNotGuessLatestCanonicalText() {
		String billId = "BIL/us/congress/119/hr/123";
		BillText introduced = BillText.factory(billId, null, "introduced", LocalDate.of(2026, 1, 1), "IH", BillTextFormat.CONGRESS_BILL_XML);
		BillText reported = BillText.factory(billId, null, "reported", LocalDate.of(2026, 2, 1), "RH", BillTextFormat.CONGRESS_BILL_XML);

		BillText unrecognizedOldText = BillText.factory(billId, 12345, "old", LocalDate.of(2026, 3, 1), "SOMETHING-12345", BillTextFormat.TEXT);

		assertTrue(fetcher.selectCanonicalMigrationTarget(unrecognizedOldText, List.of(introduced, reported)).isEmpty());
	}
}
