package us.poliscore.service.storage;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.DefaultBean;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.Persistable;
import us.poliscore.service.MemoryObjectService;

@ApplicationScoped
@DefaultBean
public class CachedDynamoDbService implements ApplicationDataStoreIF
{
	@Inject
	private MemoryObjectService memory;
	
	@Inject
	private DynamoDbPersistenceService dynamodb;
	
	@Inject
	private ObjectMapper mapper;

	@Override
	public void put(Persistable obj) {
		memory.put(obj);
		dynamodb.put(obj);
	}

	@Override
	@SneakyThrows
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		if (memory.exists(id, clazz))
		{
			return memory.get(id, clazz);
		}
		
		Optional<T> result = dynamodb.get(id, clazz);
		
		if (result.isPresent())
		{
			try {
				memory.put(mapper.treeToValue(mapper.valueToTree(result.get()), clazz));
			} catch(Throwable t) {
				Log.error(t);
			}
		}
		
		return result;
	}

	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz) {
		// You really can't check the memory here since if it's a legislator it could have been imported into memory from usc 
		
		return dynamodb.exists(id, clazz);
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz) {
		return dynamodb.query(clazz);
	}
	
	@SneakyThrows
	public <T extends Persistable> PaginatedList<T> query(Class<T> clazz, String sessionKey, int pageSize, String index, Boolean ascending, String exclusiveStartKey, String sortKey)
	{
		val list = dynamodb.query(clazz, sessionKey, pageSize, index, ascending, exclusiveStartKey, sortKey);
		
		for (T obj : list) {
			memory.put(mapper.treeToValue(mapper.valueToTree(obj), clazz));
		}
		
		return list;
	}
	
}
