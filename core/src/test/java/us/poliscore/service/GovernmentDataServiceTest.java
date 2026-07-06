package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import us.poliscore.legiscan.exception.LegiscanException;
import us.poliscore.legiscan.exception.QuotaExceededException;
import us.poliscore.model.BuildReport;

class GovernmentDataServiceTest {

	@Test
	void recognizesLegiscanQuotaExceededAlert() {
		var exception = new QuotaExceededException(
				"Alert response returned from legiscan [API key has exceeded maximum query count for July 2026 (30,015 of 30,000); limit resets August 1st [Creating additional keys to bypass this limit will result in suspended access]]");

		assertTrue(BuildReport.isLegiscanQuotaExceeded(exception));
	}

	@Test
	void rejectsOtherLegiscanAlerts() {
		var exception = new LegiscanException("Alert response returned from legiscan [Something else broke]");

		assertFalse(BuildReport.isLegiscanQuotaExceeded(exception));
	}

	@Test
	void rejectsNonLegiscanFailures() {
		assertFalse(BuildReport.isLegiscanQuotaExceeded(new RuntimeException("boom")));
	}

	@Test
	void legiscanQuotaExceededFatalIsNotBlocking() {
		var report = new BuildReport();
		report.fatal(new QuotaExceededException(
				"Alert response returned from legiscan [API key has exceeded maximum query count for July 2026 (30,015 of 30,000); limit resets August 1st [Creating additional keys to bypass this limit will result in suspended access]]"));

		assertTrue(report.hasFatal());
		assertFalse(report.hasBlockingFatal());
	}

	@Test
	void otherFatalIsBlocking() {
		var report = new BuildReport();
		report.fatal(new RuntimeException("boom"));

		assertTrue(report.hasBlockingFatal());
	}
}
