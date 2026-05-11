package us.poliscore.service.storage.repository;

import java.util.List;
import java.util.Optional;

import us.poliscore.model.Persistable;
import us.poliscore.service.storage.PaginatedList;

public interface PostgresPersistableRepository<T extends Persistable> {

	Class<T> getPersistableClass();

	void put(T obj);

	default void putIfLatest(T obj) {
		put(obj);
	}

	Optional<T> get(String id);

	boolean exists(String id);

	default List<T> query() {
		throw unsupported("query");
	}

	default PaginatedList<T> query(String datasetKey, int pageSize, String index, Boolean ascending, String exclusiveStartKey, String sortKey) {
		throw unsupported("paginated query");
	}

	default List<T> query(int pageSize, String index, Boolean ascending, String startKey, String sortKey, String storageBucket) {
		throw unsupported("raw query");
	}

	default long count() {
		throw unsupported("count");
	}

	private UnsupportedOperationException unsupported(String operation) {
		return new UnsupportedOperationException(getPersistableClass().getSimpleName() + " does not support Postgres operation: " + operation);
	}
}