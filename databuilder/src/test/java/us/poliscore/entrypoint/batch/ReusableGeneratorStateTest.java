package us.poliscore.entrypoint.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import us.poliscore.bill.InterpretationRequest;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.session.SessionInterpretation;

class ReusableGeneratorStateTest {

	@Test
	void legislatorGeneratorClearsStateBetweenRuns() throws Exception {
		var generator = new BatchLegislatorRequestGenerator();
		requests(generator).add(null);
		setField(generator, "skipped", 12L);

		generator.resetRunState();

		assertTrue(requests(generator).isEmpty());
		assertEquals(0L, getField(generator, "skipped"));
	}

	@Test
	void pressGeneratorClearsAllRunScopedState() throws Exception {
		var generator = new PressBillInterpretationRequestGenerator();
		requests(generator).add(null);
		billSet(generator, "dirtyBills").add(null);
		billSet(generator, "queriedBills").add(null);
		setField(generator, "totalRequests", 7L);
		setField(generator, "totalQueries", 9);

		generator.resetRunState();

		assertTrue(requests(generator).isEmpty());
		assertTrue(billSet(generator, "dirtyBills").isEmpty());
		assertTrue(billSet(generator, "queriedBills").isEmpty());
		assertEquals(0L, getField(generator, "totalRequests"));
		assertEquals(0, getField(generator, "totalQueries"));
	}

	@Test
	void pressGeneratorReturnsARequestSnapshot() throws Exception {
		var generator = new PressBillInterpretationRequestGenerator();

		var result = generator.process(List.of());

		assertNotSame(requests(generator), result);
		result.add(null);
		assertTrue(requests(generator).isEmpty());
	}

	@Test
	void pressDatasetResetPreservesBuildWideQueryBudget() throws Exception {
		var generator = new PressBillInterpretationRequestGenerator();
		setField(generator, "totalQueries", 9);
		requests(generator).add(null);

		generator.resetDatasetState();

		assertEquals(9, getField(generator, "totalQueries"));
		assertTrue(requests(generator).isEmpty());
	}

	@Test
	void responseImporterClearsAllRunScopedState() throws Exception {
		var importer = new BatchOpenAIResponseImporter();
		stringSet(importer, "importedBills").add("stale-bill");
		billList(importer, "interpretedBillsWithErrors").add(null);
		sessionMap(importer, "sessionInterpMap").put("stale-session", null);
		stringList(importer, "erroredLines").add("stale-line");
		stringSet(importer, "recalculatedLegislatorDatasets").add("us/congress/119");

		importer.beginImport();

		assertTrue(stringSet(importer, "importedBills").isEmpty());
		assertTrue(billList(importer, "interpretedBillsWithErrors").isEmpty());
		assertTrue(sessionMap(importer, "sessionInterpMap").isEmpty());
		assertTrue(stringList(importer, "erroredLines").isEmpty());
		assertTrue(stringSet(importer, "recalculatedLegislatorDatasets").isEmpty());
	}

	@SuppressWarnings("unchecked")
	private List<InterpretationRequest> requests(Object target) throws Exception {
		return (List<InterpretationRequest>) getField(target, "requests");
	}

	@SuppressWarnings("unchecked")
	private Set<Bill> billSet(Object target, String name) throws Exception {
		return (Set<Bill>) getField(target, name);
	}

	@SuppressWarnings("unchecked")
	private Set<String> stringSet(Object target, String name) throws Exception {
		return (Set<String>) getField(target, name);
	}

	@SuppressWarnings("unchecked")
	private List<Bill> billList(Object target, String name) throws Exception {
		return (List<Bill>) getField(target, name);
	}

	@SuppressWarnings("unchecked")
	private List<String> stringList(Object target, String name) throws Exception {
		return (List<String>) getField(target, name);
	}

	@SuppressWarnings("unchecked")
	private Map<String, SessionInterpretation> sessionMap(Object target, String name) throws Exception {
		return (Map<String, SessionInterpretation>) getField(target, name);
	}

	private Object getField(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
