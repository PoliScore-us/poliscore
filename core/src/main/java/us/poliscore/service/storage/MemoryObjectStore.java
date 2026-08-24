package us.poliscore.service.storage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.SneakyThrows;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;

public class MemoryObjectStore implements ObjectStorageServiceIF {
	
	protected Map<String, Persistable> memoryStore = new ConcurrentHashMap<String,Persistable>();
	
	public void put(Persistable obj)
	{
		Persistable.validate(obj);
		
		if (obj instanceof Bill) { ((Bill)obj).setText(null); }
		
		memoryStore.put(obj.getId(), obj);
	}

	public void clearSessions(Set<String> sessionKeys) {
		if (sessionKeys == null || sessionKeys.isEmpty()) return;

		memoryStore.entrySet().removeIf(entry -> sessionKeys.stream()
				.anyMatch(sessionKey -> belongsToSession(entry.getKey(), sessionKey)));
	}

	private boolean belongsToSession(String id, String sessionKey) {
		if (id == null || sessionKey == null) return false;
		return id.contains("/" + sessionKey + "/") || id.endsWith("/" + sessionKey);
	}
	
	@SuppressWarnings("unchecked")
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		if (memoryStore.containsKey(id))
		{
			return Optional.of((T) memoryStore.get(id));
		}
		else
		{
			return Optional.empty();
		}
	}
	
	public <T extends Persistable> long count(Class<T> clazz)
	{
		return query(clazz).size();
	}
	
	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz)
	{
		return memoryStore.containsKey(id);
	}
	
	@SuppressWarnings("unchecked")
	@SneakyThrows
	public <T extends Persistable> List<T> query(Class<T> clazz)
	{
		return memoryStore.values().stream().filter(o -> o.getClass().equals(clazz)).map(o -> (T) o).toList();
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
	    // Get all instances of this type from the in-memory store
	    List<T> all = this.query(clazz);
	    if (all.isEmpty()) return all;

	    // Build comparator: by sortKey if provided, else by Persistable#getId()
	    Comparator<T> cmp;
	    if (sortKey != null && !sortKey.isBlank()) {
	        // Key extractor returns Object; comparator handles Comparable vs toString()
	        cmp = Comparator.comparing(
	            (T o) -> readProperty(o, sortKey),
	            Comparator.nullsFirst(MemoryObjectStore::compareObjects)
	        );
	    } else {
	        cmp = Comparator.comparing(
	            (T o) -> ((Persistable) o).getId(),
	            Comparator.nullsFirst(Comparator.naturalOrder())
	        );
	    }
	    if (!ascending) cmp = cmp.reversed();

	    // Sort
	    List<T> sorted = new ArrayList<>(all);
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
	    for (String prefix : new String[] {"get", "is"}) {
	        try {
	            Method m = target.getClass().getMethod(prefix + suffix);
	            m.setAccessible(true);
	            return m.invoke(target);
	        } catch (NoSuchMethodException ignore) {}
	    }
	    Class<?> c = target.getClass();
	    while (c != null && c != Object.class) {
	        try {
	            Field f = c.getDeclaredField(name);
	            f.setAccessible(true);
	            return f.get(target);
	        } catch (NoSuchFieldException ignore) {
	            c = c.getSuperclass();
	        }
	    }
	    // Fallback: use id if available
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
