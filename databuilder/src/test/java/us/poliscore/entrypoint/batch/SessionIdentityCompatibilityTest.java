package us.poliscore.entrypoint.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import us.poliscore.PoliscoreCompositeDataset;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.dataset.LegiscanDatasetProvider;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.entrypoint.DatabaseBuilderConfig;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.session.SessionInterpretation;
import us.poliscore.service.BillService;
import us.poliscore.service.SessionInfoService;
import us.poliscore.service.storage.LocalCachedS3Service;

class SessionIdentityCompatibilityTest {

	private List<LegislativeSession> previousSessions;

	@BeforeEach
	void snapshotSessions() {
		previousSessions = SessionInfoService.getSessions();
	}

	@AfterEach
	void restoreSessions() throws Exception {
		var field = SessionInfoService.class.getDeclaredField("cachedSessions");
		field.setAccessible(true);
		field.set(null, previousSessions);
	}

	// Freeze existing session codes and key conventions independently of date getters.
	@ParameterizedTest
	@CsvSource(value = {
		"us/congress, 117, 2021, 2022, true, 117, IH",
		"us/congress, 118, 2023, 2024, true, 118, LEGACY",
		"us/congress, 118, 2023, 2024, true, 118, IH",
		"us/congress, 119, 2025, 2026, true, 119, IH",
		"us/congress, 119, 2025, 2026, true, 119, LEGACY",
		"us/co, 2173, 2025, 2025, true, 2173, IH",
		"us/co, 2224, 2025, 2025, false, 2173, IH",
		"us/co, 2243, 2026, 2026, true, 2243, IH",
		"us/az, 2155, 2025, 2025, true, 2155, IH",
		"us/az, 2235, 2026, 2026, true, 2235, IH",
		"us/nm, 2251, 2026, 2026, true, 2251, IH",
		"us/tx, 2160, 2025, 2026, true, 2160, IH",
		"us/tx, 2160, 2025, 2026, true, 2160, LEGACY",
		"us/ga, 2167, 2025, 2026, true, 2167, IH"
	}, nullValues = "LEGACY")
	void existingObjectsRemainDiscoverableAndAreNotQueuedAsNewBills(String namespaceValue, int code,
			int startYear, int endYear, boolean regular, int regularCode, String version) throws Exception {
		var namespace = LegislativeNamespace.of(namespaceValue);
		var state = LegiscanDatasetProvider.namespaceToState(namespace);
		var session = LegiscanDatasetProvider.buildSession(regular, code, state, startYear, endYear);
		assertEquals(String.valueOf(code), session.getCode());
		var config = new DeploymentConfig(namespace, endYear, 1f, true);
		var child = new PoliscoreDataset(session, config);
		PoliscoreDatasetIF dataset = child;
		if (!regular) {
			var regularSession = LegiscanDatasetProvider.buildSession(true, regularCode, state, startYear, endYear);
			dataset = new PoliscoreCompositeDataset(config, List.of(new PoliscoreDataset(regularSession, config), child));
		}
		SessionInfoService.buildSessions(List.of(dataset));
		assertEquals(endYear, dataset.getEndYear());
		assertEquals(namespaceValue + "/" + code, session.getKey());
		assertEquals(namespaceValue + "/" + (regular ? (namespaceValue.equals("us/congress") ? code : endYear) : regularCode), dataset.getKey());
		assertEquals(String.valueOf(regularCode), SessionInfoService.lookupRegularSession(namespace, endYear).getCode());

		String scope = namespaceValue + "/" + code;
		String billId = "BIL/" + scope + "/hr/1";
		var bill = new Bill();
		bill.setId(Bill.generateId(namespace, session.getCode(), "HR", 1));
		assertEquals(billId, bill.getId());
		assertEquals("BIL/" + namespaceValue + "/" + (namespaceValue.equals("us/congress") ? code : endYear), bill.getStorageBucket());
		assertEquals("LEG/" + namespaceValue + "/" + regularCode + "/person", Legislator.generateId(namespace, dataset.getRegularSession().getCode(), "person"));
		assertEquals("LIT/" + namespaceValue + "/" + regularCode + "/person", LegislatorInterpretation.generateId(namespace, dataset.getRegularSession().getCode(), "person"));
		assertEquals("SIT/" + namespaceValue + "/" + regularCode, SessionInterpretation.generateId(namespace, dataset.getRegularSession().getCode()));

		// The 118th Congress keeps legacy interpretation keys even for versioned text.
		boolean congress118 = namespaceValue.equals("us/congress") && code == 118;
		boolean legacy = congress118 || version == null;
		String interpretationId = "BIT/" + scope + "/hr/1" + (congress118 ? "" : version == null ? "-polisc" : "/" + version);
		String textId = "BTX/" + scope + "/hr/1" + (version == null ? "" : "/" + version);
		assertEquals(interpretationId, BillInterpretation.generateId(billId, version, null));
		assertEquals(interpretationId + (legacy ? "-2" : "/2"), BillInterpretation.generateId(billId, version, 2));
		assertEquals("BIT/" + scope + "/hr/1" + (congress118 ? "" : "-polisc"), BillInterpretation.generateId(billId, null, null));
		assertEquals(textId, BillText.generateId(billId, version));

		var stored = new SavedObjects();
		dataset.optimizeExists(stored, BillInterpretation.class);
		dataset.optimizeExists(stored, BillText.class);
		assertTrue(stored.optimized.contains("BIT/" + scope));
		assertTrue(stored.optimized.contains("BTX/" + scope));
		if (!regular) assertTrue(stored.optimized.contains("BIT/" + namespaceValue + "/" + regularCode));
		var text = BillText.factory(billId, null, "Existing bill text", LocalDate.of(startYear, 2, 1), version, BillTextFormat.TEXT);
		text.setId(textId);
		stored.objects.put(textId, text);
		var interpretation = new BillInterpretation();
		interpretation.setId(interpretationId);
		interpretation.setBillId(billId);
		interpretation.setSourceBillTextVersion(version);
		stored.objects.put(interpretationId, interpretation);
		assertEquals(interpretationId + ".json", stored.objectKey(interpretationId));

		var service = new BillService();
		inject(service, BillService.class, "s3", stored);
		assertEquals(interpretationId, service.getBillInterpretationId(bill));
		assertSame(interpretation, service.getInterpretation(bill).orElseThrow());
		assertTrue(stored.queries.contains("BIT/" + scope + "/hr/1"));

		var newBill = new Bill();
		newBill.setId(Bill.generateId(namespace, session.getCode(), "HR", 2));
		var newText = BillText.factory(newBill.getId(), null, "New bill text", LocalDate.of(startYear, 2, 2), version, BillTextFormat.TEXT);
		stored.objects.put(newText.getId(), newText);
		var generator = new BatchBillRequestGenerator();
		inject(generator, BatchBillRequestGenerator.class, "billService", service);
		generator.pressBillInterpGenerator = new PressBillInterpretationRequestGenerator();
		var criteria = BatchBillRequestGenerator.BillGenerationCriteria.defaultCriteria();
		criteria.REFRESH_STALE_ANALYSES = new DatabaseBuilderConfig().isRefreshStaleBillAnalyses();
		assertEquals(List.of(newBill.getId()), criteria.refine(List.of(bill, newBill).stream(), generator, true).map(Bill::getId).toList());

		// Calendar metadata and constitutional term dates cannot rename stored objects.
		if (namespaceValue.equals("us/congress")) {
			session.setStartDate(LocalDate.of(startYear, 1, 3));
			session.setEndDate(LocalDate.of(endYear + 1, 1, 3));
			assertEquals("us/congress/" + code, dataset.getKey());
			assertEquals(billId, Bill.generateId(namespace, session.getCode(), "HR", 1));
			assertSame(interpretation, service.getInterpretation(bill).orElseThrow());
			assertEquals(List.of(newBill.getId()), criteria.refine(List.of(bill, newBill).stream(), generator, true).map(Bill::getId).toList());
		}
	}

	private void inject(Object object, Class<?> type, String name, Object value) throws Exception {
		var field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(object, value);
	}

	private static class SavedObjects extends LocalCachedS3Service {
		final Map<String, Persistable> objects = new LinkedHashMap<>();
		final List<String> queries = new ArrayList<>();
		final List<String> optimized = new ArrayList<>();

		String objectKey(String id) { return getObjectKey(id); }

		@Override public <T extends Persistable> Optional<T> get(String id, Class<T> type) {
			return Optional.ofNullable(objects.get(id)).filter(type::isInstance).map(type::cast);
		}
		@Override public <T extends Persistable> boolean exists(String id, Class<T> type) {
			return get(id, type).isPresent();
		}
		@Override public <T extends Persistable> boolean existsByPrefix(Class<T> type, String sessionKey, String prefix) {
			return !query(type, sessionKey, prefix).isEmpty();
		}
		@Override public <T extends Persistable> void optimizeExists(Class<T> type, String sessionKey) {
			optimized.add(Persistable.getClassStorageBucket(type, sessionKey));
		}
		@Override public <T extends Persistable> List<T> query(Class<T> type, String sessionKey, String prefix) {
			String fullPrefix = Persistable.getClassStorageBucket(type, sessionKey) + "/" + prefix;
			queries.add(fullPrefix);
			return objects.values().stream().filter(type::isInstance).map(type::cast).filter(o -> o.getId().startsWith(fullPrefix)).toList();
		}
		@Override public void put(Persistable object) { fail("Compatibility checks must not write interpretations"); }
	}
}
