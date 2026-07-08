package us.poliscore.model.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BillTextIdentityTest {

	@Test
	void storedCongressVersionRecognizesOldUscGpoCodesAsCanonical() {
		assertEquals("IH", BillTextIdentity.canonicalCongressVersionFromStoredVersion("IH", "BIL/us/congress/119/hr/123").orElseThrow());
		assertEquals("RH", BillTextIdentity.canonicalCongressVersionFromStoredVersion("rh", "BIL/us/congress/119/hr/123").orElseThrow());
		assertEquals("RH2", BillTextIdentity.canonicalCongressVersionFromStoredVersion("rh2", "BIL/us/congress/119/hr/123").orElseThrow());
		assertEquals("EH1S", BillTextIdentity.canonicalCongressVersionFromStoredVersion("eh1s", "BIL/us/congress/119/hr/123").orElseThrow());
	}

	@Test
	void storedCongressVersionRecognizesGpoFileNames() {
		assertEquals("RH2", BillTextIdentity.canonicalCongressVersionFromStoredVersion("BILLS-119hr123rh2.xml", "BIL/us/congress/119/hr/123").orElseThrow());
		assertEquals("EH1S", BillTextIdentity.canonicalCongressVersionFromStoredVersion("BILLS-119hr123eh1s.xml", "BIL/us/congress/119/hr/123").orElseThrow());
	}

	@Test
	void storedCongressVersionRecognizesDeterministicLegiscanVersions() {
		assertEquals("IH", BillTextIdentity.canonicalCongressVersionFromStoredVersion("INTRODUCED-12345", "BIL/us/congress/119/hr/123").orElseThrow());
		assertEquals("IS", BillTextIdentity.canonicalCongressVersionFromStoredVersion("INTRODUCED-12345", "BIL/us/congress/119/s/123").orElseThrow());
		assertEquals("EH", BillTextIdentity.canonicalCongressVersionFromStoredVersion("ENGROSSED-12345", "BIL/us/congress/119/hr/123").orElseThrow());
		assertEquals("ES", BillTextIdentity.canonicalCongressVersionFromStoredVersion("ENGROSSED-12345", "BIL/us/congress/119/s/123").orElseThrow());
		assertEquals("ENR", BillTextIdentity.canonicalCongressVersionFromStoredVersion("ENROLLED-12345", "BIL/us/congress/119/hr/123").orElseThrow());
	}

	@Test
	void storedCongressVersionDoesNotGuessAmbiguousLegiscanVersions() {
		assertTrue(BillTextIdentity.canonicalCongressVersionFromStoredVersion("AMENDED-12345", "BIL/us/congress/119/hr/123").isEmpty());
		assertTrue(BillTextIdentity.canonicalCongressVersionFromStoredVersion("COMMITTEE_SUBSTITUTE-12345", "BIL/us/congress/119/hr/123").isEmpty());
		assertTrue(BillTextIdentity.canonicalCongressVersionFromStoredVersion("FISCAL_NOTE-12345", "BIL/us/congress/119/hr/123").isEmpty());
	}
}
