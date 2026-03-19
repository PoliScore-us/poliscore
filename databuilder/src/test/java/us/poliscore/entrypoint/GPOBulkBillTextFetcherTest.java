package us.poliscore.entrypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

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
}
