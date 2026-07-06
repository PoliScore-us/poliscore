package us.poliscore.model.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class BillInterpretationGenerationTest {

	@Test
	void firstGenerationUsesGenerationTime() {
		LocalDateTime generatedAt = LocalDateTime.of(2026, 6, 24, 8, 0);
		BillInterpretation interpretation = interpretation("BIT/us/congress/119/hr/1/IH");

		interpretation.recordGeneration(generatedAt, null);

		assertEquals(generatedAt, interpretation.getFirstGeneratedAt());
		assertEquals(generatedAt, interpretation.getLastUpdate());
	}

	@Test
	void regenerationPreservesFirstGenerationTime() {
		LocalDateTime firstGeneratedAt = LocalDateTime.of(2026, 6, 20, 8, 0);
		LocalDateTime regeneratedAt = LocalDateTime.of(2026, 6, 24, 8, 0);
		BillInterpretation existing = interpretation("BIT/us/congress/119/hr/1/IH");
		existing.setFirstGeneratedAt(firstGeneratedAt);
		existing.setLastUpdate(LocalDateTime.of(2026, 6, 22, 8, 0));
		BillInterpretation replacement = interpretation(existing.getId());

		replacement.recordGeneration(regeneratedAt, existing);

		assertEquals(firstGeneratedAt, replacement.getFirstGeneratedAt());
		assertEquals(regeneratedAt, replacement.getLastUpdate());
	}

	@Test
	void legacyInterpretationUsesItsLastUpdateAsFirstGenerationTime() {
		LocalDateTime legacyLastUpdate = LocalDateTime.of(2026, 6, 20, 8, 0);
		BillInterpretation existing = interpretation("BIT/us/congress/119/hr/1/IH");
		existing.setLastUpdate(legacyLastUpdate);
		BillInterpretation replacement = interpretation(existing.getId());

		replacement.recordGeneration(LocalDateTime.of(2026, 6, 24, 8, 0), existing);

		assertEquals(legacyLastUpdate, replacement.getFirstGeneratedAt());
	}

	@Test
	void billUsesEarliestGenerationAcrossTextVersions() {
		BillInterpretation introducedVersion = interpretation("BIT/us/congress/119/hr/1/IH");
		introducedVersion.setFirstGeneratedAt(LocalDateTime.of(2026, 6, 21, 8, 0));
		introducedVersion.setLastUpdate(LocalDateTime.of(2026, 6, 24, 8, 0));
		BillInterpretation enrolledVersion = interpretation("BIT/us/congress/119/hr/1/EH");
		enrolledVersion.setFirstGeneratedAt(LocalDateTime.of(2026, 6, 23, 8, 0));
		enrolledVersion.setLastUpdate(LocalDateTime.of(2026, 6, 23, 8, 0));
		Bill bill = new Bill();
		bill.setInterpretations(List.of(enrolledVersion, introducedVersion));

		assertEquals(introducedVersion.getFirstGeneratedAt(), bill.getFirstInterpretationGeneratedAt());
	}

	private BillInterpretation interpretation(String id) {
		BillInterpretation interpretation = new BillInterpretation();
		interpretation.setId(id);
		interpretation.setBillId(id.replace("BIT/", "BIL/").substring(0, id.lastIndexOf('/')));
		return interpretation;
	}
}
