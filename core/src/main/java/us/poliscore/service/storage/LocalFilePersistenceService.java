package us.poliscore.service.storage;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.apache.commons.io.FileUtils;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.SneakyThrows;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.Persistable;

@ApplicationScoped
public class LocalFilePersistenceService implements ObjectStorageServiceIF
{

	protected File getLocalStorage()
	{
//		return new File(Environment.getDeployedPath(), "../store");
		
		return new File(PoliscoreUtil.APP_DATA, "store");
	}
	
	protected File getStore(String idClassPrefix)
	{
//		if (Bill.class.equals(clazz)) return new File(getLocalStorage(), "bills");
//		else if (BillInterpretation.class.equals(clazz)) return new File(getLocalStorage(), "interpretations");
//		else if (Legislator.class.equals(clazz)) return new File(getLocalStorage(), "legislators");
//		else return new File(getLocalStorage(), clazz.getName());
		
		File f = new File(getLocalStorage(), idClassPrefix);
		
		if (!f.exists())
		{
			f.mkdirs();
		}
		
		return f;
	}
	
	protected File fileFor(String id) {
		File f = new File(getLocalStorage(), id + ".json");
		
		if (!f.getParentFile().exists()) {
			f.getParentFile().mkdirs();
		}
		
		return f;
	}
	
	@SneakyThrows
	public void put(Persistable obj) {
		File f = fileFor(obj.getId());
		
		var mapper = PoliscoreUtil.getObjectMapper();
		mapper.writerWithDefaultPrettyPrinter().writeValue(f, obj);
		
//		Log.info("Wrote file to " + out.getAbsolutePath());
	}
	
	@SneakyThrows
	public <T extends Persistable> void delete(String id, Class<T> clazz) {
		File f = fileFor(id);
		
		if (!f.exists())
			return;
		
		f.delete();
	}

	@SneakyThrows
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		File f = fileFor(id);
		
		if (!f.exists())
			return Optional.empty();
		
		var mapper = PoliscoreUtil.getObjectMapper();
		return Optional.of(mapper.readValue(f, clazz));
	}

	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz) {
		File f = fileFor(id);
		return f.exists();
	}

	@Override
	@SneakyThrows
	public <T extends Persistable> long count(Class<T> clazz) {
	    // Classes expose where they live via a static ID_CLASS_PREFIX (e.g., "bills", "legislators", etc.)
	    final String idClassPrefix = (String) clazz.getField("ID_CLASS_PREFIX").get(null);
	    final File objectStore = getStore(idClassPrefix);

	    if (!objectStore.exists()) {
	        return 0L;
	    }

	    // Count all JSON files recursively (ids may create nested directories).
	    return (long) FileUtils.listFiles(objectStore, new String[]{"json"}, true).size();
	}

	@Override
	@SneakyThrows
	public <T extends Persistable> List<T> query(Class<T> clazz) {
	    final String idClassPrefix = (String) clazz.getField("ID_CLASS_PREFIX").get(null);
	    final File objectStore = getStore(idClassPrefix);

	    if (!objectStore.exists()) return List.of();

	    var mapper = PoliscoreUtil.getObjectMapper();

	    // Read all *.json under the prefix directory (recursively)
	    return FileUtils
	        .listFiles(objectStore, new String[] { "json" }, true)
	        .stream()
	        .map(f -> {
	            try {
	                return mapper.readValue(f, clazz);
	            } catch (IOException e) {
	                throw new RuntimeException("Failed to read " + f.getAbsolutePath(), e);
	            }
	        })
	        .toList();
	}

	@Override
	@SneakyThrows
	public <T extends Persistable> List<T> query(
	    Class<T> clazz,
	    int pageSize,
	    String index,
	    Boolean ascending,
	    String startKey,
	    String sortKey,
	    String storageBucket
	) {
	    // Load everything of this type from disk
	    List<T> all = this.query(clazz);
	    if (all.isEmpty()) return all;

	    // Build comparator: by sortKey if provided, else by Persistable#getId()
	    Comparator<T> cmp;
	    if (sortKey != null && !sortKey.isBlank()) {
	        // Extract arbitrary property via getter/field; handle Comparable vs toString
	        cmp = Comparator.comparing(
	            (T o) -> readProperty(o, sortKey),
	            Comparator.nullsFirst(LocalFilePersistenceService::compareObjects)
	        );
	    } else {
	        cmp = Comparator.comparing(
	            (T o) -> ((Persistable) o).getId(),
	            Comparator.nullsFirst(Comparator.naturalOrder())
	        );
	    }
	    if (!ascending) cmp = cmp.reversed();

	    // Sort
	    List<T> sorted = new java.util.ArrayList<>(all);
	    sorted.sort(cmp);

	    // If a startKey (cursor) is provided, advance to the first item AFTER it (by id)
	    int startIdx = 0;
	    if (startKey != null && !startKey.isBlank()) {
	        for (int i = 0; i < sorted.size(); i++) {
	            Persistable p = (Persistable) sorted.get(i);
	            if (startKey.equals(p.getId())) {
	                startIdx = i + 1;
	                break;
	            }
	        }
	        if (startIdx >= sorted.size()) return List.of();
	    }

	    // Page size handling
	    int limit = pageSize > 0 ? pageSize : sorted.size();
	    int endIdx = Math.min(sorted.size(), startIdx + limit);
	    return sorted.subList(startIdx, endIdx);
	}

	/** Read a property via getter or field; may return any Object (possibly null). */
	@SneakyThrows
	private static Object readProperty(Object target, String name) {
	    String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
	    // Try JavaBean getters first
	    for (String prefix : new String[] {"get", "is"}) {
	        try {
	            var m = target.getClass().getMethod(prefix + suffix);
	            m.setAccessible(true);
	            return m.invoke(target);
	        } catch (NoSuchMethodException ignore) {}
	    }
	    // Try declared field, walking up the hierarchy
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
	    // Fallback: id if Persistable
	    if (target instanceof Persistable p) return p.getId();
	    return null;
	}

	/** Null-safe comparison that prefers Comparable, else falls back to toString(). */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static int compareObjects(Object a, Object b) {
	    if (a == b) return 0;
	    if (a == null) return -1;
	    if (b == null) return 1;

	    // If same class and Comparable, use it
	    if (a instanceof Comparable && a.getClass().isInstance(b)) {
	        return ((Comparable) a).compareTo(b);
	    }
	    // Fallback to string compare
	    return a.toString().compareTo(b.toString());
	}
	
}
