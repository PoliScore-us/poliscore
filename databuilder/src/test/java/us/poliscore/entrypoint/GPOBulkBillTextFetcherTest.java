package us.poliscore.entrypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GPOBulkBillTextFetcherTest {

	private final GPOBulkBillTextFetcher fetcher = new GPOBulkBillTextFetcher();

	@TempDir
	Path tempDir;

	@Test
	void parseDateReadsBillDublinCoreDate() throws Exception {
		Path xml = writeXml("bill.xml", """
				<?xml version="1.0"?>
				<bill>
				  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
				    <dublinCore>
				      <dc:date>2025-04-09</dc:date>
				    </dublinCore>
				  </metadata>
				</bill>
				""");

		assertEquals(LocalDate.of(2025, 4, 9), fetcher.parseDate(xml.toFile()));
	}

	@Test
	void parseDateReadsResolutionDublinCoreDate() throws Exception {
		Path xml = writeXml("resolution.xml", """
				<?xml version="1.0"?>
				<resolution>
				  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
				    <dublinCore>
				      <dc:date>2025-02-04</dc:date>
				    </dublinCore>
				  </metadata>
				</resolution>
				""");

		assertEquals(LocalDate.of(2025, 2, 4), fetcher.parseDate(xml.toFile()));
	}

	@Test
	void parseDateReadsAmendmentDublinCoreDate() throws Exception {
		Path xml = writeXml("amendment.xml", """
				<?xml version="1.0"?>
				<amendment-doc>
				  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
				    <dublinCore>
				      <dc:date>2026-02-11</dc:date>
				    </dublinCore>
				  </metadata>
				</amendment-doc>
				""");

		assertEquals(LocalDate.of(2026, 2, 11), fetcher.parseDate(xml.toFile()));
	}

	@Test
	void parseDateFallsBackToActionDateWhenDublinCoreDateMissing() throws Exception {
		Path xml = writeXml("fallback.xml", """
				<?xml version="1.0"?>
				<amendment-doc>
				  <action>
				    <action-date date="20260211">February 11, 2026</action-date>
				  </action>
				</amendment-doc>
				""");

		assertEquals(LocalDate.of(2026, 2, 11), fetcher.parseDate(xml.toFile()));
	}
	
	@Test
	void parseDateFallsBackToAttestationDateWhenOtherDatesMissing() throws Exception {
		Path xml = writeXml("attestation.xml", """
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
				""");

		assertEquals(LocalDate.of(2025, 9, 15), fetcher.parseDate(xml.toFile()));
	}

	@Test
	void extractBillNumberIgnoresVersionSuffixDigits() {
		assertEquals(3426, fetcher.extractBillNumber("119", "hr", "BILLS-119hr3426eh1s.xml"));
		assertEquals(7643, fetcher.extractBillNumber("118", "hr", "BILLS-118hr7643rh2.xml"));
		assertEquals(3426, fetcher.extractBillNumber("119", "hr", "BILLS-119hr3426rfs2.xml"));
	}

	private Path writeXml(String filename, String xml) throws Exception {
		Path file = tempDir.resolve(filename);
		Files.writeString(file, xml);
		return file;
	}
}
