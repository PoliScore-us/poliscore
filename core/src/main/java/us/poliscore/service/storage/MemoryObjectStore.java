package us.poliscore.service.storage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;

public class MemoryObjectStore implements ObjectStorageServiceIF {
	
	protected Map<String, Persistable> memoryStore = new HashMap<String,Persistable>();
	
	public void put(Persistable obj)
	{
		if (obj instanceof Bill) { ((Bill)obj).setText(null); }
		
		memoryStore.put(obj.getId(), obj);
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
}
