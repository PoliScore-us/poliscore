package us.poliscore.service.storage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.legislator.LegislatorMediaReference;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.model.session.SessionInterpretation;
import us.poliscore.service.BillService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.storage.repository.BillRepository;
import us.poliscore.service.storage.repository.LegislatorRepository;
import us.poliscore.service.storage.repository.SessionInterpretationRepository;

@ApplicationScoped
public class PostgresSyncService {

	private static final boolean FORCE_REFRESH_ALL = false;
	
	private static final int HOT_REFRESH_LIMIT = 1000;
	private static final int BILL_UPSERT_CHUNK_SIZE = 1000;
	private static final int LEGISLATOR_UPSERT_CHUNK_SIZE = 100;

	@Inject
	private PostgresPersistenceService postgres;

	@Inject
	private BillRepository billRepository;

	@Inject
	private LegislatorRepository legislatorRepository;

	@Inject
	private SessionInterpretationRepository sessionInterpretationRepository;

	@Inject
	private LocalCachedS3Service s3;

	@Inject
	private BillService billService;

	@Inject
	private LegislatorService legService;

	public boolean isEnabled() {
		return postgres.isEnabled();
	}

	@SneakyThrows
	public void syncPostgresWithS3(PoliscoreDatasetIF dataset) {
		dataset.optimizeExists(s3, BillInterpretation.class);
		dataset.optimizeExists(s3, LegislatorInterpretation.class);
		dataset.optimizeExists(s3, LegislatorMediaReference.class);
		dataset.optimizeExists(s3, PressInterpretation.class);
		
		
		Log.info("Making sure that our postgres database is up-to-date with what exists on s3 (" + dataset.getNamespace().getNamespace() + " " + dataset.getEndYear() + ")");
		Set<String> updatedSet = syncBillsToPostgres(dataset);
		refreshHotBills(dataset, updatedSet);
		syncLegislatorsToPostgres(dataset);
		syncSessionInterpretationsToPostgres(dataset);
	}

	private Set<String> syncBillsToPostgres(PoliscoreDatasetIF dataset) {
		var lastUpdateMap = billRepository.getLastUpdateMap(dataset.getKey());
		Set<String> updatedSet = new HashSet<>();
		var billsToUpsert = new ArrayList<Bill>();
		int syncedBillCount = 0;
		for (Bill bill : dataset.query(Bill.class)) {
			val interp = billService.getInterpretation(bill);
			LocalDateTime billLastUpdate;
			if (interp.isPresent()) {
				if (interp.get().getLastUpdate() == null) {
					if (interp.get().getLastPressQuery() == null) {
						interp.get().setLastUpdate(LocalDateTime.now());
					} else {
						interp.get().setLastUpdate(interp.get().getLastPressQuery().atStartOfDay());
					}
					s3.put(interp.get());
				}

				billLastUpdate = latest(
						getBillStatusLastUpdate(bill),
						interp.get().getLastUpdate());
			} else {
				bill.setInterpretation(null);
				bill.setInterpretations(null);
				bill.setTexts(billService.getBillTexts(bill));
				billLastUpdate = getBillStatusLastUpdate(bill);
				if (bill.getLastUpdate() == null || (billLastUpdate != null && billLastUpdate.isAfter(bill.getLastUpdate()))) {
					bill.setLastUpdate(billLastUpdate);
				}
			}

			var billExistsInPostgres = lastUpdateMap.containsKey(bill.getId());
			var existingLastUpdate = lastUpdateMap.get(bill.getId());
			if (FORCE_REFRESH_ALL || !billExistsInPostgres || !Objects.equals(billLastUpdate, existingLastUpdate)) {
				if (interp.isPresent()) {
					billService.applyInterpretation(bill, interp.get());
				}
				billsToUpsert.add(bill);
				lastUpdateMap.put(bill.getId(), bill.getLastUpdate());
				updatedSet.add(bill.getId());

				if (billsToUpsert.size() >= BILL_UPSERT_CHUNK_SIZE) {
					syncedBillCount += flushBillsToPostgres(billsToUpsert);
				}
			}
		}
		if (!billsToUpsert.isEmpty()) {
			syncedBillCount += flushBillsToPostgres(billsToUpsert);
		}
		Log.info("Synced " + syncedBillCount + " changed bills to postgres");
		return updatedSet;
	}

	private LocalDateTime latest(LocalDateTime first, LocalDateTime second) {
		if (first == null) return second;
		if (second == null) return first;
		return first.isAfter(second) ? first : second;
	}

	private LocalDateTime getBillStatusLastUpdate(Bill bill) {
		if (bill.getLastUpdate() != null) {
			return bill.getLastUpdate();
		}
		if (bill.getLastActionDate() != null) {
			return bill.getLastActionDate().atStartOfDay();
		}
		if (bill.getIntroducedDate() != null) {
			return bill.getIntroducedDate().atStartOfDay();
		}
		return null;
	}

	private void refreshHotBills(PoliscoreDatasetIF dataset, Set<String> updatedSet) {
		Log.info("Refreshing top " + HOT_REFRESH_LIMIT + " hot bills in postgres");
		var hottestBills = new ArrayList<Bill>();
		for (Bill bill : billRepository.query(dataset.getKey(), HOT_REFRESH_LIMIT, Persistable.OBJECT_BY_HOT_INDEX, false, null, null)) {
			if (!updatedSet.contains(bill.getId())) {
				hottestBills.add(bill);
			}
		}
		billRepository.putAll(hottestBills);
		Log.info("Refreshed " + hottestBills.size() + " hot bills in postgres");
	}

	private void syncLegislatorsToPostgres(PoliscoreDatasetIF dataset) {
		Log.info("Pushing legislators to postgres");
		Set<String> retainedLegislatorIds = new HashSet<>();
		var legislatorsToUpsert = new ArrayList<Legislator>();
		int syncedLegislatorCount = 0;
		for (var leg : dataset.query(Legislator.class)) {
			retainedLegislatorIds.add(leg.getId());
			if (leg.getInterpretation() != null) {
				legService.applyInterpretation(leg, leg.getInterpretation());
				legislatorsToUpsert.add(leg);

				if (legislatorsToUpsert.size() >= LEGISLATOR_UPSERT_CHUNK_SIZE) {
					syncedLegislatorCount += flushLegislatorsToPostgres(legislatorsToUpsert);
				}
			}
		}
		if (!legislatorsToUpsert.isEmpty()) {
			syncedLegislatorCount += flushLegislatorsToPostgres(legislatorsToUpsert);
		}
		String storageBucket = Persistable.getClassStorageBucket(Legislator.class, dataset.getKey());
		int deletedLegislatorCount = legislatorRepository.deleteMissingFromStorageBucket(storageBucket, retainedLegislatorIds);
		Log.info("Pushed " + syncedLegislatorCount + " legislators to postgres and pruned " + deletedLegislatorCount + " stale legislators");
	}

	private void syncSessionInterpretationsToPostgres(PoliscoreDatasetIF dataset) {
		Log.info("Pushing session interps to postgres");
		var sessionInterpretationsToUpsert = new ArrayList<SessionInterpretation>();
		for (var session : dataset.query(SessionInterpretation.class)) {
			if (session.isComplete(dataset.hasIndependentPartyMembers())) {
				sessionInterpretationsToUpsert.add(session);
			}
		}
		sessionInterpretationRepository.putAll(sessionInterpretationsToUpsert);
		Log.info("Pushed " + sessionInterpretationsToUpsert.size() + " updated session interpretations to postgres");
	}

	private int flushBillsToPostgres(ArrayList<Bill> billsToUpsert) {
		int flushed = billsToUpsert.size();
		billRepository.putAll(billsToUpsert);
		billsToUpsert.clear();
		return flushed;
	}

	private int flushLegislatorsToPostgres(ArrayList<Legislator> legislatorsToUpsert) {
		int flushed = legislatorsToUpsert.size();
		legislatorRepository.putAll(legislatorsToUpsert);
		legislatorsToUpsert.clear();
		return flushed;
	}
}
