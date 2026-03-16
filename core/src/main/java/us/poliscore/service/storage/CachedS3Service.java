package us.poliscore.service.storage;

import java.util.List;
import java.util.Optional;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.val;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import us.poliscore.model.Persistable;
import us.poliscore.service.MemoryObjectService;

@ApplicationScoped
@DefaultBean
public class CachedS3Service extends S3PersistenceService
{
	@Inject
	private MemoryObjectService memory;
	
	@Override
	public void put(Persistable obj) {
		memory.put(obj);
		super.put(obj);
	}

	@Override
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		if (memory.exists(id, clazz))
		{
			return memory.get(id, clazz);
		}
		
		Optional<T> result = super.get(id, clazz);
		
		if (result.isPresent())
		{
			memory.put(result.get());
		}
		
		return result;
	}
	
	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz)
	{
		return memory.exists(id, clazz) || super.exists(id, clazz);
	}

	public <T extends Persistable> void optimizeExists(Class<T> clazz, String sessionKey) {
		super.optimizeExists(clazz, sessionKey);
	}

	@Override
	public <T extends Persistable> long count(Class<T> clazz) {
		return super.count(clazz);
	}
	
	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz) {
		return super.query(clazz);
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz, int pageSize, String index, Boolean ascending,
			String startKey, String sortKey, String storageBucket) {
		return super.query(clazz, pageSize, index, ascending, startKey, sortKey, storageBucket);
	}
	
}
