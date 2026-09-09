package us.poliscore.entrypoint.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import us.poliscore.PoliscoreUtil;
import us.poliscore.model.AIAggregateInterpretationMetadata;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;

class BatchBillRequestGeneratorTest {

	@Test
	void identifiesInterpretationsForOlderBillTextVersionsAsOutOfDate() {
		assertTrue(BatchBillRequestGenerator.isInterpretationOutOfDate(interpretation("IH"), text("EH")));
		assertFalse(BatchBillRequestGenerator.isInterpretationOutOfDate(interpretation("EH"), text("EH")));
	}

	@Test
	void versionedTextTreatsLegacyInterpretationWithoutSourceVersionAsOutOfDate() {
		assertTrue(BatchBillRequestGenerator.isInterpretationOutOfDate(interpretation(null), text("EH")));
	}

	@Test
	void unversionedLegacyTextDoesNotCauseAnUnresolvableRefreshLoop() {
		assertFalse(BatchBillRequestGenerator.isInterpretationOutOfDate(interpretation(null), text(null)));
	}

	@Test
	void directRefreshCriteriaContinuesToIncludeStaleAnalysesByDefault() {
		assertTrue(BatchBillRequestGenerator.BillGenerationCriteria.defaultCriteria().REFRESH_STALE_ANALYSES);
	}

	@Test
	void disabledStaleRefreshStillProcessesNewBillsButSkipsExistingStaleBills() {
		BatchBillRequestGenerator.BillGenerationCriteria criteria =
				BatchBillRequestGenerator.BillGenerationCriteria.defaultCriteria();
		criteria.REFRESH_STALE_ANALYSES = false;

		assertFalse(BatchBillRequestGenerator.shouldProcessBill(criteria, interpretation("IH"), text("EH"), false));
		assertFalse(BatchBillRequestGenerator.shouldProcessBill(criteria, interpretation("IH"), text("EH"), true));
		assertTrue(BatchBillRequestGenerator.shouldProcessBill(criteria, null, text("EH"), false));

		criteria.FORCE_REFRESH = true;
		assertTrue(BatchBillRequestGenerator.shouldProcessBill(criteria, interpretation("IH"), text("EH"), false));
	}

	@Test
	void copiedInterpretationRecordsReuseProvenanceInMetadataOnly() {
		BillInterpretation source = interpretation("IH");
		source.setId("BIT/us/congress/119/hr/1/IH");
		source.setBillId("BIL/us/congress/119/hr/1");
		source.setMetadata(AIAggregateInterpretationMetadata.construct("openai", "test-model", 1, false, java.util.List.of()));
		BillInterpretation alias = PoliscoreUtil.getObjectMapper().convertValue(source, BillInterpretation.class);
		alias.setId("BIT/us/congress/119/hr/1/EH");
		alias.setSourceBillTextVersion("EH");

		BatchBillRequestGenerator.recordReuseProvenance(alias, source);

		assertEquals(source.getId(), alias.getMetadata().getReusedFromInterpretationId());
		assertEquals("IH", alias.getMetadata().getReusedFromBillTextVersion());
		assertEquals(BatchBillRequestGenerator.REUSE_REASON_IDENTICAL_SUBSTANTIVE_TEXT,
				alias.getMetadata().getReuseReason());
		assertNull(source.getMetadata().getReusedFromInterpretationId());
		assertNull(source.getMetadata().getReusedFromBillTextVersion());
		assertNull(source.getMetadata().getReuseReason());
	}

	private BillInterpretation interpretation(String sourceVersion) {
		BillInterpretation interpretation = new BillInterpretation();
		interpretation.setSourceBillTextVersion(sourceVersion);
		return interpretation;
	}

	private BillText text(String version) {
		return BillText.factory("BIL/us/congress/119/hr/1", null, "Bill text", LocalDate.of(2026, 1, 1), version, BillTextFormat.TEXT);
	}
}
