package us.poliscore;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	protected Logger logger = LoggerFactory.getLogger(PoliscoreCompositeDataset.class);
	
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
		for (var dataset : datasets) {
			if (((PoliscoreDataset)dataset).getSession().isRegular()) {
				dataset.put(obj);
				return;
			}
		}
		
		// If we couldn't find a "regular session" dataset, just grab the first one.
		logger.error("Not sure which dataset to add to... Picking a random one. This might be a bug?");
		datasets.get(0).put(obj);
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
	public String toString() {
		return this.getDescription();
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
	public boolean isCurrent() {
		return isYearWithin(Year.now().getValue());
	}
	
	@Override
	public boolean isBuild() {
		return this.config.getBuild();
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

	@Override
	public <T extends Persistable> List<T> query(
	    Class<T> clazz,
	    int pageSize,
	    String index,
	    Boolean ascending,
	    String startKey,
	    String sortKey,
	    String storageBucket
	) {
	    // 1) Gather from all underlying datasets
	    final var all = new ArrayList<T>();
	    for (ObjectStorageServiceIF ds : datasets) {
	        List<T> part = ds.query(clazz);
	        if (part != null && !part.isEmpty()) {
	            all.addAll(part);
	        }
	    }
	    if (all.isEmpty()) return List.of();

	    // 2) Comparator (by sortKey if provided, else by id)
	    boolean asc = (ascending == null) ? true : ascending.booleanValue();

	    java.util.Comparator<T> cmp;
	    if (sortKey != null && !sortKey.isBlank()) {
	        cmp = java.util.Comparator.comparing(
	            (T o) -> readProperty(o, sortKey),
	            java.util.Comparator.nullsFirst(PoliscoreCompositeDataset::compareObjects)
	        );
	    } else {
	        cmp = java.util.Comparator.comparing(
	            (T o) -> ((Persistable) o).getId(),
	            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())
	        );
	    }
	    if (!asc) cmp = cmp.reversed();

	    all.sort(cmp);

	    // 3) Cursor: advance past startKey (by id)
	    int startIdx = 0;
	    if (startKey != null && !startKey.isBlank()) {
	        for (int i = 0; i < all.size(); i++) {
	            Persistable p = all.get(i);
	            if (startKey.equals(p.getId())) {
	                startIdx = i + 1;
	                break;
	            }
	        }
	        if (startIdx >= all.size()) return List.of();
	    }

	    // 4) Page
	    int limit = pageSize > 0 ? pageSize : (all.size() - startIdx);
	    int endIdx = Math.min(all.size(), startIdx + limit);
	    return all.subList(startIdx, endIdx);
	}

	/** Read a property via getter or field; may return any Object (possibly null). */
	@lombok.SneakyThrows
	private static Object readProperty(Object target, String name) {
	    String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);

	    // Try getters
	    for (String prefix : new String[] {"get", "is"}) {
	        try {
	            var m = target.getClass().getMethod(prefix + suffix);
	            m.setAccessible(true);
	            return m.invoke(target);
	        } catch (NoSuchMethodException ignore) {}
	    }

	    // Try field up the hierarchy
	    Class<?> c = target.getClass();
	    while (c != null && c != Object.class) {
	        try {
	            var f = c.getDeclaredField(name);
	            f.setAccessible(true);
	            return f.get(target);
	        } catch (NoSuchFieldException ignore) {
	            c = c.getSuperclass();
	        }
	    }

	    // Fallback to id when available
	    if (target instanceof Persistable p) return p.getId();
	    return null;
	}

	/** Null-safe comparison that prefers Comparable, else falls back to toString(). */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static int compareObjects(Object a, Object b) {
	    if (a == b) return 0;
	    if (a == null) return -1;
	    if (b == null) return 1;

	    if (a instanceof Comparable && a.getClass().isInstance(b)) {
	        return ((Comparable) a).compareTo(b);
	    }
	    return a.toString().compareTo(b.toString());
	}

}
