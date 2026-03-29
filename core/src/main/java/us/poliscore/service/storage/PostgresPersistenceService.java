package us.poliscore.service.storage;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillIssueStat;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorIssueStat;
import us.poliscore.model.session.SessionInterpretation;
import us.poliscore.service.storage.repository.BillRepository;
import us.poliscore.service.storage.repository.LegislatorRepository;
import us.poliscore.service.storage.repository.SessionInterpretationRepository;

@ApplicationScoped
public class PostgresPersistenceService implements ObjectStorageServiceIF {

	@Inject
	Instance<DataSource> dataSourceInstance;

	@Inject
	BillRepository billRepository;

	@Inject
	LegislatorRepository legislatorRepository;

	@Inject
	SessionInterpretationRepository sessionInterpretationRepository;

	public boolean isEnabled() {
		return dataSourceInstance.isResolvable();
	}

	@Override
	public <T extends Persistable> void put(T obj) {
		if (obj instanceof Bill bill) {
			billRepository.put(bill);
			return;
		}
		if (obj instanceof Legislator legislator) {
			legislatorRepository.put(legislator);
			return;
		}
		if (obj instanceof SessionInterpretation sessionInterpretation) {
			sessionInterpretationRepository.put(sessionInterpretation);
			return;
		}

		throw unsupported(obj.getClass());
	}

	@Override
	public <T extends Persistable> void putIfLatest(T obj) {
		if (obj instanceof Bill bill) {
			billRepository.putIfLatest(bill);
			return;
		}
		if (obj instanceof Legislator legislator) {
			legislatorRepository.putIfLatest(legislator);
			return;
		}
		if (obj instanceof SessionInterpretation sessionInterpretation) {
			sessionInterpretationRepository.putIfLatest(sessionInterpretation);
			return;
		}

		throw unsupported(obj.getClass());
	}

	@Override
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz) {
		if (Bill.class.equals(clazz)) {
			return cast(billRepository.get(id));
		}
		if (Legislator.class.equals(clazz)) {
			return cast(legislatorRepository.get(id));
		}
		if (SessionInterpretation.class.equals(clazz)) {
			return cast(sessionInterpretationRepository.get(id));
		}

		throw unsupported(clazz);
	}

	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz) {
		if (Bill.class.equals(clazz)) {
			return billRepository.exists(id);
		}
		if (Legislator.class.equals(clazz)) {
			return legislatorRepository.exists(id);
		}
		if (SessionInterpretation.class.equals(clazz)) {
			return sessionInterpretationRepository.exists(id);
		}

		throw unsupported(clazz);
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz) {
		return query(clazz, -1, null, null, null, null, Persistable.getClassStorageBucket(clazz, null));
	}

	public <T extends Persistable> PaginatedList<T> query(Class<T> clazz, String datasetKey, int pageSize, String index, Boolean ascending, String exclusiveStartKey, String sortKey) {
		if (Bill.class.equals(clazz)) {
			return castList(billRepository.query(datasetKey, pageSize, index, ascending, exclusiveStartKey, sortKey));
		}
		if (Legislator.class.equals(clazz)) {
			return castList(legislatorRepository.query(datasetKey, pageSize, index, ascending, exclusiveStartKey, sortKey));
		}
		if (SessionInterpretation.class.equals(clazz)) {
			return castList(sessionInterpretationRepository.query(datasetKey, pageSize, index, ascending, exclusiveStartKey, sortKey));
		}

		throw unsupported(clazz);
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz, int pageSize, String index, Boolean ascending, String startKey, String sortKey, String storageBucket) {
		if (Bill.class.equals(clazz)) {
			return castRawList(billRepository.query(pageSize, index, ascending, startKey, sortKey, storageBucket));
		}
		if (BillIssueStat.class.equals(clazz)) {
			return castRawList(billRepository.queryIssueStats(pageSize, index, ascending, startKey, storageBucket));
		}
		if (Legislator.class.equals(clazz)) {
			return castRawList(legislatorRepository.query(pageSize, index, ascending, startKey, sortKey, storageBucket));
		}
		if (LegislatorIssueStat.class.equals(clazz)) {
			return castRawList(legislatorRepository.queryIssueStats(pageSize, index, ascending, startKey, storageBucket));
		}
		if (SessionInterpretation.class.equals(clazz)) {
			return castRawList(sessionInterpretationRepository.query(pageSize, index, ascending, startKey, sortKey, storageBucket));
		}

		throw unsupported(clazz);
	}

	@Override
	public <T extends Persistable> long count(Class<T> clazz) {
		if (Bill.class.equals(clazz)) {
			return billRepository.count();
		}
		if (Legislator.class.equals(clazz)) {
			return legislatorRepository.count();
		}
		if (SessionInterpretation.class.equals(clazz)) {
			return sessionInterpretationRepository.count();
		}

		throw unsupported(clazz);
	}

	private UnsupportedOperationException unsupported(Class<?> clazz) {
		return new UnsupportedOperationException(
				clazz.getSimpleName() + " does not have a dedicated Postgres repository. Add one before using it with Postgres.");
	}

	@SuppressWarnings("unchecked")
	private <T extends Persistable> Optional<T> cast(Optional<? extends Persistable> value) {
		return (Optional<T>) value;
	}

	@SuppressWarnings("unchecked")
	private <T extends Persistable> PaginatedList<T> castList(PaginatedList<? extends Persistable> value) {
		return (PaginatedList<T>) value;
	}

	@SuppressWarnings("unchecked")
	private <T extends Persistable> List<T> castRawList(List<? extends Persistable> value) {
		return (List<T>) value;
	}
}
