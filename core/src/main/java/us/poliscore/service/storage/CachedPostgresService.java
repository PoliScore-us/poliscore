package us.poliscore.service.storage;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.session.SessionInterpretation;
import us.poliscore.service.MemoryObjectService;
import us.poliscore.service.storage.repository.BillRepository;
import us.poliscore.service.storage.repository.LegislatorRepository;
import us.poliscore.service.storage.repository.SessionInterpretationRepository;

@ApplicationScoped
public class CachedPostgresService implements ObjectStorageServiceIF
{
	@Inject
	private MemoryObjectService memory;

	@Inject
	private PostgresPersistenceService postgres;

	@Inject
	private ObjectMapper mapper;

	@Inject
	private BillRepository billRepository;

	@Inject
	private LegislatorRepository legislatorRepository;

	@Inject
	private SessionInterpretationRepository sessionInterpretationRepository;

	public boolean isEnabled()
	{
		return postgres.isEnabled();
	}

	@Override
	public void put(Persistable obj)
	{
		memory.put(obj);
		if (obj instanceof Bill bill) {
			billRepository.put(bill);
		}
		else if (obj instanceof Legislator legislator) {
			legislatorRepository.put(legislator);
		}
		else if (obj instanceof SessionInterpretation sessionInterpretation) {
			sessionInterpretationRepository.put(sessionInterpretation);
		}
		else {
			postgres.put(obj);
		}
	}

	@Override
	public void putIfLatest(Persistable obj)
	{
		if (obj instanceof Bill bill) {
			billRepository.putIfLatest(bill);
		}
		else if (obj instanceof Legislator legislator) {
			legislatorRepository.putIfLatest(legislator);
		}
		else if (obj instanceof SessionInterpretation sessionInterpretation) {
			sessionInterpretationRepository.putIfLatest(sessionInterpretation);
		}
		else {
			postgres.putIfLatest(obj);
		}
		memory.put(obj);
	}

	@Override
	@SneakyThrows
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		if (memory.exists(id, clazz)) {
			return memory.get(id, clazz);
		}

		Optional<T> result = getFromBackingStore(id, clazz);
		if (result.isPresent()) {
			try {
				memory.put(mapper.treeToValue(mapper.valueToTree(result.get()), clazz));
			}
			catch (Throwable t) {
				Log.error(t);
			}
		}

		return result;
	}

	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz)
	{
		if (Bill.class.equals(clazz)) {
			return billRepository.exists(id);
		}
		if (Legislator.class.equals(clazz)) {
			return legislatorRepository.exists(id);
		}
		if (SessionInterpretation.class.equals(clazz)) {
			return sessionInterpretationRepository.exists(id);
		}

		return postgres.exists(id, clazz);
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz)
	{
		List<T> list = queryFromBackingStore(clazz, -1, null, null, null, null, Persistable.getClassStorageBucket(clazz, null));
		cacheResults(clazz, list);
		return list;
	}

	public <T extends Persistable> PaginatedList<T> query(Class<T> clazz, String datasetKey, int pageSize, String index, Boolean ascending, String exclusiveStartKey, String sortKey)
	{
		val list = queryFromBackingStore(clazz, datasetKey, pageSize, index, ascending, exclusiveStartKey, sortKey);
		cacheResults(clazz, list);
		return list;
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz, int pageSize, String index, Boolean ascending, String startKey, String sortKey, String storageBucket)
	{
		List<T> list = queryFromBackingStore(clazz, pageSize, index, ascending, startKey, sortKey, storageBucket);
		cacheResults(clazz, list);
		return list;
	}

	@Override
	public <T extends Persistable> long count(Class<T> clazz)
	{
		if (Bill.class.equals(clazz)) {
			return billRepository.count();
		}
		if (Legislator.class.equals(clazz)) {
			return legislatorRepository.count();
		}
		if (SessionInterpretation.class.equals(clazz)) {
			return sessionInterpretationRepository.count();
		}

		return postgres.count(clazz);
	}

	@SuppressWarnings("unchecked")
	private <T extends Persistable> Optional<T> getFromBackingStore(String id, Class<T> clazz)
	{
		if (Bill.class.equals(clazz)) {
			return (Optional<T>) billRepository.get(id);
		}
		if (Legislator.class.equals(clazz)) {
			return (Optional<T>) legislatorRepository.get(id);
		}
		if (SessionInterpretation.class.equals(clazz)) {
			return (Optional<T>) sessionInterpretationRepository.get(id);
		}

		return postgres.get(id, clazz);
	}

	@SuppressWarnings("unchecked")
	private <T extends Persistable> PaginatedList<T> queryFromBackingStore(Class<T> clazz, String datasetKey, int pageSize, String index, Boolean ascending, String exclusiveStartKey, String sortKey)
	{
		if (Bill.class.equals(clazz)) {
			return (PaginatedList<T>) billRepository.query(datasetKey, pageSize, index, ascending, exclusiveStartKey, sortKey);
		}
		if (Legislator.class.equals(clazz)) {
			return (PaginatedList<T>) legislatorRepository.query(datasetKey, pageSize, index, ascending, exclusiveStartKey, sortKey);
		}
		if (SessionInterpretation.class.equals(clazz)) {
			return (PaginatedList<T>) sessionInterpretationRepository.query(datasetKey, pageSize, index, ascending, exclusiveStartKey, sortKey);
		}

		return postgres.query(clazz, datasetKey, pageSize, index, ascending, exclusiveStartKey, sortKey);
	}

	@SuppressWarnings("unchecked")
	private <T extends Persistable> List<T> queryFromBackingStore(Class<T> clazz, int pageSize, String index, Boolean ascending, String startKey, String sortKey, String storageBucket)
	{
		if (Bill.class.equals(clazz)) {
			return (List<T>) billRepository.query(pageSize, index, ascending, startKey, sortKey, storageBucket);
		}
		if (Legislator.class.equals(clazz)) {
			return (List<T>) legislatorRepository.query(pageSize, index, ascending, startKey, sortKey, storageBucket);
		}
		if (SessionInterpretation.class.equals(clazz)) {
			return (List<T>) sessionInterpretationRepository.query(pageSize, index, ascending, startKey, sortKey, storageBucket);
		}

		return postgres.query(clazz, pageSize, index, ascending, startKey, sortKey, storageBucket);
	}

	@SneakyThrows
	private <T extends Persistable> void cacheResults(Class<T> clazz, List<T> list)
	{
		for (T obj : list) {
			memory.put(mapper.treeToValue(mapper.valueToTree(obj), clazz));
		}
	}
}
