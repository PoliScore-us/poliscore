package us.poliscore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import us.poliscore.model.Persistable;
import us.poliscore.service.storage.ObjectStorageServiceIF;

public class PoliscoreCompositeDataset implements ObjectStorageServiceIF {

	protected List<PoliscoreDataset> datasets = new ArrayList<>();

	public PoliscoreCompositeDataset(List<PoliscoreDataset> datasets) {
		this.datasets = datasets;
	}

	@Override
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz) {
		for (ObjectStorageServiceIF dataset : datasets) {
			Optional<T> result = dataset.get(id, clazz);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	@Override
	public <T extends Persistable> void put(T obj) {
//		for (ObjectStorageServiceIF dataset : datasets) {
//			dataset.put(obj);
//		}
		
		throw new UnsupportedOperationException("TODO : Composite dataset is read only (for now)");
	}

	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz) {
		for (ObjectStorageServiceIF dataset : datasets) {
			if (dataset.exists(id, clazz)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz) {
		List<T> results = new ArrayList<>();
		for (ObjectStorageServiceIF dataset : datasets) {
			List<T> partialResults = dataset.query(clazz);
			if (partialResults != null) {
				results.addAll(partialResults);
			}
		}
		return results;
	}
}
