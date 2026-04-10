package us.poliscore.service.storage;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import us.poliscore.model.Persistable;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.service.MemoryObjectService;

@ApplicationScoped
public class CachedPostgresService implements ObjectStorageServiceIF {

	@Inject
	MemoryObjectService memory;

	@Inject
	PostgresPersistenceService postgres;

	@Inject
	ObjectMapper mapper;

	public boolean isEnabled() {
		return postgres.isEnabled();
	}

	@Override
	public void put(Persistable obj) {
		postgres.put(obj);
		memory.put(obj);
	}

	@Override
	public void putIfLatest(Persistable obj) {
		postgres.putIfLatest(obj);
		memory.put(obj);
	}

	@Override
	@SneakyThrows
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz) {
		if (memory.exists(id, clazz)) {
			return memory.get(id, clazz);
		}

		Optional<T> result = postgres.get(id, clazz);
		if (result.isPresent()) {
			cacheResult(clazz, result.get());
		}

		return result;
	}

	@Override
	@SneakyThrows
	public Optional<Legislator> getLegislatorFirstPage(String id) {
		if (memory.exists(id, Legislator.class)) {
			return memory.get(id, Legislator.class);
		}

		return postgres.getLegislatorFirstPage(id);
	}

	@Override
	@SneakyThrows
	public Optional<Legislator> getLegislatorAllInteractions(String id) {
		if (memory.exists(id, Legislator.class)) {
			return memory.get(id, Legislator.class);
		}

		Optional<Legislator> result = postgres.getLegislatorAllInteractions(id);
		if (result.isPresent()) {
			cacheResult(Legislator.class, result.get());
		}

		return result;
	}

	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz) {
		if (memory.exists(id, clazz)) {
			return true;
		}

		return postgres.exists(id, clazz);
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz) {
		List<T> list = postgres.query(clazz);
		cacheQueryResults(clazz, list);
		return list;
	}

	public <T extends Persistable> PaginatedList<T> query(Class<T> clazz, String datasetKey, int pageSize, String index, Boolean ascending, String exclusiveStartKey, String sortKey) {
		PaginatedList<T> list = postgres.query(clazz, datasetKey, pageSize, index, ascending, exclusiveStartKey, sortKey);
		cacheQueryResults(clazz, list);
		return list;
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz, int pageSize, String index, Boolean ascending, String startKey, String sortKey, String storageBucket) {
		List<T> list = postgres.query(clazz, pageSize, index, ascending, startKey, sortKey, storageBucket);
		cacheQueryResults(clazz, list);
		return list;
	}

	@Override
	public <T extends Persistable> long count(Class<T> clazz) {
		return postgres.count(clazz);
	}

	@SneakyThrows
	private <T extends Persistable> void cacheResults(Class<T> clazz, List<T> list) {
		for (T obj : list) {
			cacheResult(clazz, obj);
		}
	}

	private <T extends Persistable> void cacheQueryResults(Class<T> clazz, List<T> list) {
		if (Legislator.class.equals(clazz)) {
			return;
		}

		cacheResults(clazz, list);
	}

	@SneakyThrows
	private <T extends Persistable> void cacheResult(Class<T> clazz, T obj) {
		try {
			memory.put(mapper.treeToValue(mapper.valueToTree(obj), clazz));
		}
		catch (Throwable t) {
			Log.error(t);
		}
	}
}
