package us.poliscore.service.storage;

import java.util.List;
import java.util.Optional;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.model.Persistable;
import us.poliscore.service.MemoryObjectService;
import us.poliscore.service.storage.S3PersistenceService.QueryCriteria;

@ApplicationScoped
@DefaultBean
public class LocalCachedS3Service extends S3PersistenceService implements ApplicationDataStoreIF
{
	@Inject
	private MemoryObjectService memory;
	
	@Inject
	private LocalFilePersistenceService local;

	@Override
	public void put(Persistable obj) {
		memory.put(obj);
		local.put(obj);
		super.put(obj);
	}

	@Override
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		if (memory.exists(id, clazz))
		{
			return memory.get(id, clazz);
		}
		
		if (local.exists(id, clazz))
		{
			return local.get(id, clazz);
		}
		
		Optional<T> result = super.get(id, clazz);
		
		if (result.isPresent())
		{
			memory.put(result.get());
			local.put(result.get());
		}
		
		return result;
	}
	
	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz)
	{
		return memory.exists(id, clazz) || local.exists(id, clazz) || super.exists(id, clazz);
	}
	
	public <T extends Persistable> boolean existsByPrefix(Class<T> clazz, String sessionKey, String objectKeyPrefix) {
		return super.existsByPrefix(clazz, sessionKey, objectKeyPrefix);
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz) {
		return super.query(clazz);
	}
	
	public <T extends Persistable> List<T> query(Class<T> clazz, String sessionKey) {
		return super.query(clazz, sessionKey);
	}
	
	public <T extends Persistable> List<T> query(Class<T> clazz, String sessionKey, String objectKey) {
		return super.query(clazz, sessionKey, objectKey);
	}
	
	public <T extends Persistable> List<T> query(Class<T> clazz, String sessionKey, QueryCriteria criteria) {
		return super.query(clazz, sessionKey, criteria);
	}
	
	public <T extends Persistable> void optimizeExists(Class<T> clazz, String sessionKey) {
		super.optimizeExists(clazz, sessionKey);
	}
	
	public <T extends Persistable> void clearExistsOptimize(Class<T> clazz, String sessionKey) {
		super.clearExistsOptimize(clazz, sessionKey);
	}
	
	public <T extends Persistable> void delete(String id, Class<T> clazz)
	{
		super.delete(id, clazz);
		local.delete(id, clazz);
	}
	
}
