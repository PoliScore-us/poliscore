package us.poliscore.service;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.SneakyThrows;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.service.storage.MemoryObjectStore;
import us.poliscore.service.storage.ObjectStorageServiceIF;

@ApplicationScoped
public class MemoryObjectService implements ObjectStorageServiceIF {
	
	protected static MemoryObjectStore memoryStore = new MemoryObjectStore();
	
	public void put(Persistable obj)
	{
		if (obj instanceof Bill) { ((Bill)obj).setText(null); }
		
		memoryStore.put(obj);
	}
	
	@SuppressWarnings("unchecked")
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		return memoryStore.get(id, clazz);
	}
	
	public <T extends Persistable> long count(String idClassPrefix)
	{
		return memoryStore.count(idClassPrefix);
	}
	
	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz)
	{
		return memoryStore.exists(id, clazz);
	}
	
	@SneakyThrows
	public <T extends Persistable> List<T> query(Class<T> clazz)
	{
		return memoryStore.query(clazz);
	}
	
	@SneakyThrows
	public <T extends Persistable> List<T> query(Class<T> clazz, String storageBucket)
	{
		return memoryStore.query(clazz, storageBucket);
	}
	
	@SneakyThrows
	public <T extends Persistable> List<T> queryAll(Class<T> clazz)
	{
		return memoryStore.queryAll(clazz);
	}
}
