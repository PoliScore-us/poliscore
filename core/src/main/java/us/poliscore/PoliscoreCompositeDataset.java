package us.poliscore;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import lombok.val;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;
import us.poliscore.service.storage.ObjectStorageServiceIF;
import us.poliscore.service.storage.S3PersistenceService;

public class PoliscoreCompositeDataset implements ObjectStorageServiceIF, PoliscoreDatasetIF {

	protected List<PoliscoreDatasetIF> datasets = new ArrayList<>();
	
	protected DeploymentConfig config;
	
	public PoliscoreCompositeDataset(DeploymentConfig config) { this.config = config; }

	public PoliscoreCompositeDataset(DeploymentConfig config, List<PoliscoreDatasetIF> datasets) {
		this.config = config;
		this.datasets = datasets;
	}
	
	public void addDataset(PoliscoreDatasetIF dataset) {
		this.datasets.add(dataset);
	}
	
	public List<PoliscoreDatasetIF> getDatasets() {
		return this.datasets;
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
	public <T extends Persistable> long count(Class<T> clazz) {
		long count = 0;
		
		for (ObjectStorageServiceIF dataset : datasets) {
			count += dataset.count(clazz);
		}
		
		return count;
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
	
	public boolean containsSession(String sessionKey) {
		for (PoliscoreDatasetIF dataset : datasets) {
			if (dataset.containsSession(sessionKey))
				return true;
		}
		
		return false;
	}
	
	@Override
	public String getDescription() {
		return "(Composite) " + this.getNamespace().getDescription() + " " + this.getEndYear();
	}
	
	@Override
	public int getStartYear() {
	    return this.datasets.stream()
	        .mapToInt(d -> d.getStartYear())
	        .min()
	        .orElseThrow(() -> new IllegalStateException("No datasets available"));
	}

	@Override
	public int getEndYear() {
	    return this.datasets.stream()
	        .mapToInt(d -> d.getEndYear())
	        .max()
	        .orElseThrow(() -> new IllegalStateException("No datasets available"));
	}
	
	@Override
	public LegislativeNamespace getNamespace() {
		return this.datasets.get(0).getNamespace();
	}

	@Override
	public DeploymentConfig getConfig() {
		return this.config;
	}

	@Override
	public boolean hasIndependentPartyMembers() {
		for (PoliscoreDatasetIF dataset : datasets) {
			if (dataset.hasIndependentPartyMembers())
				return true;
		}
		
		return false;
	}

	@Override
	public boolean isYearWithin(int year) {
		return this.getStartYear() <= year && this.getEndYear() >= year;
	}

	@Override
	public String getCode() {
	    val regular = getRegularSession();
	    return regular != null ? regular.getCode() : String.valueOf(getEndYear());
	}
	
	@Override
	public LegislativeSession getRegularSession() {
		LegislativeSession result = null;
	    for (PoliscoreDatasetIF d : datasets) {
	    	LegislativeSession reg = d.getRegularSession();
	    	if (reg == null) continue;
	    	
	    	if (result != null) {
                throw new IllegalStateException("More than one regular session found");
            }
	    	
	    	result = reg;
	    }
	    return result;
	}

	@Override
	public String getKey() {
		return getNamespace() + "/" + getCode();
	}
	
	@Override
	public LegislativeSession getObjectSession(SessionPersistable p) {
		for (PoliscoreDatasetIF dataset : datasets) {
			if (dataset.containsSession(p.getSessionCode()))
				return dataset.getObjectSession(p);
		}
		
		throw new NoSuchElementException(p.getSessionCode());
	}
	
	@Override
	public <T extends Persistable> void optimizeExists(S3PersistenceService s3, Class<T> clazz) {
		for (PoliscoreDatasetIF dataset : datasets) {
			dataset.optimizeExists(s3, clazz);
		}
	}

	@Override
	public <T extends Persistable> void clearExistsOptimize(S3PersistenceService s3, Class<T> clazz) {
		for (PoliscoreDatasetIF dataset : datasets) {
			dataset.clearExistsOptimize(s3, clazz);
		}
	}
}
