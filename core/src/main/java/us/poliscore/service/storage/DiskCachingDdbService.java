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
public class DiskCachingDdbService implements ApplicationDataStoreIF
{
	@Inject
	private MemoryObjectService memory;
	
	@Inject
	private LocalFilePersistenceService disk;
	
	@Inject
	private DynamoDbPersistenceService ddb;

	@Override
	public void put(Persistable obj) {
		memory.put(obj);
		disk.put(obj);
		ddb.put(obj);
	}

	@Override
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		if (memory.exists(id, clazz))
		{
			return memory.get(id, clazz);
		}
		
		if (disk.exists(id, clazz))
		{
			return disk.get(id, clazz);
		}
		
		Optional<T> result = ddb.get(id, clazz);
		
		if (result.isPresent())
		{
			memory.put(result.get());
			disk.put(result.get());
		}
		
		return result;
	}
	
	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz)
	{
		return memory.exists(id, clazz) || disk.exists(id, clazz) || ddb.exists(id, clazz);
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz) {
		return ddb.query(clazz);
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz, int pageSize, String index, Boolean ascending,
			String startKey, String sortKey, String storageBucket) {
		return ddb.query(clazz, pageSize, index, ascending, startKey, sortKey, storageBucket);
	}

	@Override
	public <T extends Persistable> long count(Class<T> clazz) {
		return ddb.count(clazz);
	}
	
}
