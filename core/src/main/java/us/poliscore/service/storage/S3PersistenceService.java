package us.poliscore.service.storage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Persistable;
import us.poliscore.service.GovernmentDataService;

@ApplicationScoped
public class S3PersistenceService implements ObjectStorageServiceIF
{
	
	public static final String BUCKET_NAME = "poliscore-archive";
	
	@Inject protected GovernmentDataService data;
	
	private S3Client client;
	
	private static HashMap<String, Set<String>> objectsInBucket = new HashMap<String, Set<String>>();
	
	protected String getObjectKey(String id)
	{
		return id + ".json";
	}
	
	protected String getSessionKey(String id)
	{
		return id.split("/")[1] + "/" + id.split("/")[2] + "/" + id.split("/")[3];
	}
	
	private S3Client getClient()
	{
		if (client == null)
		{
			client = S3Client.builder()
	                .build();
		}
		
		return client;
	}
	
	@SneakyThrows
	public void put(Persistable obj)
	{
		Persistable.validate(obj);
		
		val key = getObjectKey(obj.getId());
		
		if (key.contains("null")) {
			throw new UnsupportedOperationException("Your object's id is " + key + "... Really? I don't think so.");
		}
		
        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build();

        getClient().putObject(putOb, RequestBody.fromString(PoliscoreUtil.getObjectMapper().writeValueAsString(obj)));
        
        Log.info("Uploaded to S3 " + key);
	}
	
	@SneakyThrows
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		val key = getObjectKey(id);
		
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build();

        try {
        	@Cleanup val resp = getClient().getObject(req);
        	
//        	Log.info("Retrieved " + clazz.getSimpleName() + " from S3 " + key);
        	
        	return Optional.of(PoliscoreUtil.getObjectMapper().readValue(resp, clazz));
        }
        catch (NoSuchKeyException ex)
        {
//        	Log.info(clazz.getSimpleName() + " not found on S3 " + key);
        	
        	return Optional.empty();
        }
	}
	
	@Override
	@SneakyThrows
	public <T extends Persistable> boolean exists(String id, Class<T> clazz)
	{
		String sessionKey = getSessionKey(id);
		
		val idClassPrefix = Persistable.getClassStorageBucket(clazz, sessionKey);
		if (objectsInBucket.containsKey(idClassPrefix)) return objectsInBucket.get(idClassPrefix).contains(id);
		
		val key = getObjectKey(id);
		
		try
		{
			val resp = getClient().headObject(HeadObjectRequest.builder()
					.bucket(BUCKET_NAME)
					.key(key)
					.build());
			
			return true;
		}
		catch (NoSuchKeyException ex)
		{
			return false;
		}
	}
	
	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz) {
//		return query(clazz, sessionKey, null, -1, true);
		
		throw new NotImplementedException("You want to query all objects across all legislative sessions? Really?");
	}
	
	public <T extends Persistable> List<T> query(Class<T> clazz, String sessionKey) {
		return query(clazz, sessionKey, null, -1, true);
	}
	
	public <T extends Persistable> List<T> query(Class<T> clazz, String sessionKey, String objectKey) {
		return query(clazz, objectKey, sessionKey, -1, true);
	}
	
	@SneakyThrows
	public <T extends Persistable> List<T> query(Class<T> clazz, String sessionKey, String objectKey, int pageSize, boolean ascending)
	{
	    val keys = new java.util.ArrayList<String>();
	    String continuationToken = null;
	    
	    String storageBucket = Persistable.getClassStorageBucket(clazz, sessionKey);
	    
	    String fullPrefix = storageBucket;
	    if (StringUtils.isNotBlank(objectKey))
	    	fullPrefix = storageBucket + "/" + objectKey;

	    // First: collect all matching keys
	    do {
	        val builder = ListObjectsV2Request.builder()
	                .bucket(BUCKET_NAME)
	                .prefix(fullPrefix)
	                .maxKeys(1000); // AWS maximum per request

	        if (continuationToken != null) {
	            builder.continuationToken(continuationToken);
	        }

	        val resp = getClient().listObjectsV2(builder.build());

	        for (val s3Object : resp.contents()) {
	            keys.add(s3Object.key());
	        }

	        continuationToken = resp.nextContinuationToken();
	    }
	    while (continuationToken != null);

	    // Now: sort keys if needed
	    if (!ascending) {
	        keys.sort(java.util.Collections.reverseOrder());
	    }

	    val results = new java.util.ArrayList<T>();

	    int limit = pageSize > 0 ? Math.min(pageSize, keys.size()) : keys.size(); // If pageSize <= 0, fetch all

	    for (int i = 0; i < limit; i++) {
	        val s3Key = keys.get(i);

	        val getObjectRequest = GetObjectRequest.builder()
	                .bucket(BUCKET_NAME)
	                .key(s3Key)
	                .build();

	        @Cleanup val s3ObjectStream = getClient().getObject(getObjectRequest);

	        val obj = PoliscoreUtil.getObjectMapper().readValue(s3ObjectStream, clazz);
	        results.add(obj);
	    }

	    return results;
	}

	@SneakyThrows
	public <T extends Persistable> void optimizeExists(Class<T> clazz, String sessionKey) {
		val storageBucket = Persistable.getClassStorageBucket(clazz, sessionKey);
		
		if (objectsInBucket.containsKey(storageBucket)) return;
		
		objectsInBucket.put(storageBucket, new HashSet<String>());
		
		String continuationToken = null;
		do {
			val builder = ListObjectsV2Request.builder().bucket(BUCKET_NAME)
					.prefix(storageBucket);
			
			if (continuationToken != null) {
				builder.continuationToken(continuationToken);
			}
			
			val resp = getClient().listObjectsV2(builder.build());
			
			objectsInBucket.get(storageBucket).addAll(resp.contents().stream().map(o -> FilenameUtils.getPath(o.key()) + FilenameUtils.getBaseName(o.key())).toList());
			
			continuationToken = resp.nextContinuationToken();
		}
		while(continuationToken != null);
	}
	
	@SneakyThrows
	public <T extends Persistable> void clearExistsOptimize(Class<T> clazz, String sessionKey) {
		val idClassPrefix = Persistable.getClassStorageBucket(clazz, sessionKey);
		
		objectsInBucket.remove(idClassPrefix);
	}
	
	@SneakyThrows
	public <T extends Persistable> void delete(String id, Class<T> clazz)
	{
		String sessionKey = this.getSessionKey(id);
		val key = getObjectKey(id);
		
		try
		{
			getClient().deleteObject(builder -> builder
					.bucket(BUCKET_NAME)
					.key(key));
			
			Log.info("Deleted from S3 " + key);
			
			// Update the local cache if optimizeExists has been called before
			val idClassPrefix = Persistable.getClassStorageBucket(clazz, sessionKey);
			if (objectsInBucket.containsKey(idClassPrefix))
			{
				objectsInBucket.get(idClassPrefix).remove(id);
			}
		}
		catch (NoSuchKeyException ex)
		{
			// S3 delete is idempotent, but we can optionally log
			Log.info("Attempted to delete non-existent object from S3 " + key);
		}
	}
	
}
