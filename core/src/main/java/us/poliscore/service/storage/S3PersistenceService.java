package us.poliscore.service.storage;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.NotImplementedException;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Cleanup;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.val;
import lombok.experimental.Accessors;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.Persistable;
import us.poliscore.service.GovernmentDataService;

@ApplicationScoped
public class S3PersistenceService implements ObjectStorageServiceIF
{
	
	public static final String BUCKET_NAME = "poliscore-archive";
	private static final long KNOWN_FINGERPRINT_TTL_MILLIS = 5 * 60 * 1000L;
	
	@Inject protected GovernmentDataService data;
	
	private S3Client client;
	
	private static ConcurrentMap<String, ConcurrentMap<String, S3ObjectFingerprint>> objectsInBucket = new ConcurrentHashMap<>();
	private static ConcurrentMap<String, ObservedFingerprint> knownObjectFingerprints = new ConcurrentHashMap<>();
	
	protected String getObjectKey(String id)
	{
		return id + ".json";
	}
	
	protected String getObjectIdFromKey(String key)
	{
		return key.substring(0, key.length()-5);
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
					.httpClientBuilder(UrlConnectionHttpClient.builder())
	                .build();
		}
		
		return client;
	}
	
	@SneakyThrows
	public void put(Persistable obj)
	{
		putWithFingerprint(obj);
	}

	@SneakyThrows
	protected S3ObjectFingerprint putWithFingerprint(Persistable obj)
	{
		Persistable.validate(obj);
		
		String sessionKey = getSessionKey(obj.getId());
		val idClassPrefix = Persistable.getClassStorageBucket(obj.getClass(), sessionKey);
		
		val key = getObjectKey(obj.getId());
		
		if (key.contains("null")) {
			throw new UnsupportedOperationException("Your object's id is " + key + "... Really? I don't think so.");
		}
		
		PutObjectRequest putOb = PutObjectRequest.builder()
				.bucket(BUCKET_NAME)
				.key(key)
				.build();

		String json = PoliscoreUtil.getObjectMapper().writeValueAsString(obj);
		val response = getClient().putObject(putOb, RequestBody.fromString(json));
		val fingerprint = new S3ObjectFingerprint(normalizeEtag(response.eTag()), json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
		rememberFingerprint(obj.getId(), fingerprint);

		if (objectsInBucket.containsKey(idClassPrefix))
			objectsInBucket.get(idClassPrefix).put(obj.getId(), fingerprint);

		Log.info("Uploaded to S3 " + key);
		return fingerprint;
	}
	
	@SneakyThrows
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		return getWithFingerprint(id, clazz).map(S3GetResult::object);
	}

	@SneakyThrows
	protected <T extends Persistable> Optional<S3GetResult<T>> getWithFingerprint(String id, Class<T> clazz)
	{
		String sessionKey = getSessionKey(id);
		val idClassPrefix = Persistable.getClassStorageBucket(clazz, sessionKey);
		
		val key = getObjectKey(id);
		
		// If optimize exists was called, and we know the object doesn't exist, it's actually faster to just return null then it is to go all the way to s3
		if (objectsInBucket.containsKey(idClassPrefix) && !objectsInBucket.get(idClassPrefix).containsKey(id)) return Optional.empty();
		
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build();

        try {
        	@Cleanup val resp = getClient().getObject(req);
        	
//        	Log.info("Retrieved " + clazz.getSimpleName() + " from S3 " + key);
        	
			val fingerprint = new S3ObjectFingerprint(normalizeEtag(resp.response().eTag()), resp.response().contentLength());
			rememberFingerprint(id, fingerprint);
			if (objectsInBucket.containsKey(idClassPrefix))
				objectsInBucket.get(idClassPrefix).put(id, fingerprint);
			return Optional.of(new S3GetResult<>(PoliscoreUtil.getObjectMapper().readValue(resp, clazz), fingerprint));
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
		return getRemoteFingerprint(id, clazz).isPresent();
	}

	@SneakyThrows
	protected <T extends Persistable> Optional<S3ObjectFingerprint> getRemoteFingerprint(String id, Class<T> clazz)
	{
		String sessionKey = getSessionKey(id);
		val storageBucket = Persistable.getClassStorageBucket(clazz, sessionKey);
		if (objectsInBucket.containsKey(storageBucket))
			return Optional.ofNullable(objectsInBucket.get(storageBucket).get(id));

		val known = knownObjectFingerprints.get(id);
		if (known != null && System.currentTimeMillis() - known.observedAtMillis() <= KNOWN_FINGERPRINT_TTL_MILLIS)
			return Optional.of(known.fingerprint());
		if (known != null) knownObjectFingerprints.remove(id, known);

		try {
			val response = getClient().headObject(HeadObjectRequest.builder()
					.bucket(BUCKET_NAME)
					.key(getObjectKey(id))
					.build());
			val fingerprint = new S3ObjectFingerprint(normalizeEtag(response.eTag()), response.contentLength());
			rememberFingerprint(id, fingerprint);
			return Optional.of(fingerprint);
		} catch (NoSuchKeyException ex) {
			return Optional.empty();
		} catch (S3Exception ex) {
			// HEAD responses do not include an error body, so the SDK reports a
			// missing object as a generic S3Exception rather than NoSuchKeyException.
			if (ex.statusCode() == 404) return Optional.empty();
			throw ex;
		}
	}
	
	@SneakyThrows
	public <T extends Persistable> boolean existsByPrefix(Class<T> clazz, String sessionKey, String objectKeyPrefix)
	{
		val storageBucket = Persistable.getClassStorageBucket(clazz, sessionKey);
		val fullPrefix = storageBucket + "/" + objectKeyPrefix;
		
		if (objectsInBucket.containsKey(storageBucket)) {
			return objectsInBucket.get(storageBucket).keySet().stream().anyMatch(id -> id.startsWith(fullPrefix));
		}
		
		val resp = getClient().listObjectsV2(ListObjectsV2Request.builder()
				.bucket(BUCKET_NAME)
				.prefix(fullPrefix)
				.maxKeys(1)
				.build());
		
		return !resp.contents().isEmpty();
	}
	
	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz) {
//		return query(clazz, sessionKey, null, -1, true);
		
		throw new NotImplementedException("You want to query all objects across all legislative sessions? Really?");
	}
	
	public <T extends Persistable> List<T> query(Class<T> clazz, String sessionKey) {
		return query(clazz, sessionKey, new QueryCriteria(null, null, null, -1, true));
	}
	
	public <T extends Persistable> List<T> query(Class<T> clazz, String sessionKey, String objectKey) {
		return query(clazz, sessionKey, new QueryCriteria(objectKey, null, null, -1, true));
	}
	
	@SneakyThrows
	public <T extends Persistable> List<T> query(Class<T> clazz, String sessionKey, QueryCriteria criteria)
	{
	    String storageBucket = Persistable.getClassStorageBucket(clazz, sessionKey);
	    val keys = getQueryKeys(clazz, storageBucket, criteria);

	    val results = new java.util.ArrayList<T>();

	    int limit = criteria.getPageSize() > 0 ? Math.min(criteria.getPageSize(), keys.size()) : keys.size(); // If pageSize <= 0, fetch all

	    for (int i = 0; i < limit; i++) {
	        val s3Key = keys.get(i);
	        
	        val op = get(getObjectIdFromKey(s3Key), clazz);
	        
	        if (op.isPresent())
	        	results.add(op.get());
	    }

	    return results;
	}
	
	protected <T extends Persistable> List<String> getQueryKeys(Class<T> clazz, String storageBucket, QueryCriteria criteria) {
		val optimizedKeys = getQueryKeysFromOptimizeExists(storageBucket, criteria);
		if (optimizedKeys.isPresent()) {
			return optimizedKeys.get();
		}
		
	    val keys = new java.util.ArrayList<String>();
	    String continuationToken = null;
	    val fullPrefix = buildFullPrefix(storageBucket, criteria.getObjectKeyPrefix());

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
				rememberFingerprint(getObjectIdFromKey(s3Object.key()), fingerprint(s3Object));
				Instant lastModified = s3Object.lastModified();

	            if ((criteria.getLastModifiedAfter() == null || lastModified.isAfter(criteria.getLastModifiedAfter())) &&
	                (criteria.getLastModifiedBefore() == null || lastModified.isBefore(criteria.getLastModifiedBefore()))) {
	                keys.add(s3Object.key());
	            }
	        }

	        continuationToken = resp.nextContinuationToken();
	    }
	    while (continuationToken != null);

	    if (!criteria.isAscending()) {
	        keys.sort(Collections.reverseOrder());
	    }
		
		return keys;
	}
	
	protected Optional<List<String>> getQueryKeysFromOptimizeExists(String storageBucket, QueryCriteria criteria) {
		if (criteria.getLastModifiedAfter() != null || criteria.getLastModifiedBefore() != null) {
			return Optional.empty();
		}
		
		if (!objectsInBucket.containsKey(storageBucket)) {
			return Optional.empty();
		}
		
		val fullPrefix = buildFullPrefix(storageBucket, criteria.getObjectKeyPrefix());
		Comparator<String> comparator = criteria.isAscending() ? Comparator.naturalOrder() : Comparator.reverseOrder();
		
		return Optional.of(objectsInBucket.get(storageBucket).keySet().stream()
				.filter(id -> id.startsWith(fullPrefix))
				.map(this::getObjectKey)
				.sorted(comparator)
				.toList());
	}
	
	protected String buildFullPrefix(String storageBucket, String objectKeyPrefix) {
		if (objectKeyPrefix == null) {
			return storageBucket;
		}
		
		return storageBucket + "/" + objectKeyPrefix;
	}

	@SneakyThrows
	public <T extends Persistable> void optimizeExists(Class<T> clazz, String sessionKey) {
		val storageBucket = Persistable.getClassStorageBucket(clazz, sessionKey);
		
		if (objectsInBucket.containsKey(storageBucket)) return;
		
		objectsInBucket.put(storageBucket, new ConcurrentHashMap<>());
		
		String continuationToken = null;
		do {
			val builder = ListObjectsV2Request.builder().bucket(BUCKET_NAME)
					.prefix(storageBucket);
			
			if (continuationToken != null) {
				builder.continuationToken(continuationToken);
			}
			
			val resp = getClient().listObjectsV2(builder.build());
			
			for (val object : resp.contents()) {
				String id = FilenameUtils.getPath(object.key()) + FilenameUtils.getBaseName(object.key());
				val fingerprint = fingerprint(object);
				objectsInBucket.get(storageBucket).put(id, fingerprint);
				rememberFingerprint(id, fingerprint);
			}
			
			continuationToken = resp.nextContinuationToken();
		}
		while(continuationToken != null);
	}
	
	@SneakyThrows
	public <T extends Persistable> void clearExistsOptimize(Class<T> clazz, String sessionKey) {
		val storageBucket = Persistable.getClassStorageBucket(clazz, sessionKey);
		
		objectsInBucket.remove(storageBucket);
		knownObjectFingerprints.keySet().removeIf(id -> id.equals(storageBucket) || id.startsWith(storageBucket + "/"));
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
			knownObjectFingerprints.remove(id);
		}
		catch (NoSuchKeyException ex)
		{
			// S3 delete is idempotent, but we can optionally log
			Log.info("Attempted to delete non-existent object from S3 " + key);
		}
	}

	protected Map<String, S3ObjectFingerprint> getOptimizedFingerprints(String storageBucket) {
		val fingerprints = objectsInBucket.get(storageBucket);
		return fingerprints == null ? Map.of() : Map.copyOf(fingerprints);
	}

	protected boolean hasOptimizedFingerprints(String storageBucket) {
		return objectsInBucket.containsKey(storageBucket);
	}

	private S3ObjectFingerprint fingerprint(software.amazon.awssdk.services.s3.model.S3Object object) {
		return new S3ObjectFingerprint(normalizeEtag(object.eTag()), object.size());
	}

	private void rememberFingerprint(String id, S3ObjectFingerprint fingerprint) {
		knownObjectFingerprints.put(id, new ObservedFingerprint(fingerprint, System.currentTimeMillis()));
	}

	protected static String normalizeEtag(String eTag) {
		if (eTag == null) return null;
		String normalized = eTag.trim();
		if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\""))
			normalized = normalized.substring(1, normalized.length() - 1);
		return normalized;
	}

	public record S3ObjectFingerprint(String eTag, long size) { }
	private record ObservedFingerprint(S3ObjectFingerprint fingerprint, long observedAtMillis) { }
	protected record S3GetResult<T extends Persistable>(T object, S3ObjectFingerprint fingerprint) { }
	
	@Data
	@Accessors(chain = true)
	@AllArgsConstructor
	@NoArgsConstructor
	public static class QueryCriteria {

	    private String objectKeyPrefix; // formerly objectKey

	    private Instant lastModifiedAfter;
	    private Instant lastModifiedBefore;

	    private int pageSize = 0; // <= 0 means "no limit"
	    private boolean ascending = true;

	    // Future fields:
	    // private Instant createdAfter;
	    // private Map<String, String> requiredTags;
	}

	@Override
	@SneakyThrows
	public <T extends Persistable> long count(Class<T> clazz) {
	    // For S3 storage, objects are partitioned by "storage bucket" prefix
	    // which is derived from the class and session key. Since we don't have
	    // a session key here, we count everything under the class’ root prefix.
	    String storageBucket = Persistable.getClassStorageBucket(clazz, null);

	    long total = 0L;
	    String continuationToken = null;

	    do {
	        val builder = ListObjectsV2Request.builder()
	                .bucket(BUCKET_NAME)
	                .prefix(storageBucket)
	                .maxKeys(1000);

	        if (continuationToken != null) {
	            builder.continuationToken(continuationToken);
	        }

	        val resp = getClient().listObjectsV2(builder.build());
	        total += resp.keyCount(); // number of keys returned in this page
	        continuationToken = resp.nextContinuationToken();
	    } while (continuationToken != null);

	    return total;
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
	    // Determine S3 prefix for this class (use provided storageBucket if present)
	    final String prefix = (storageBucket != null && !storageBucket.isBlank())
	        ? storageBucket
	        : Persistable.getClassStorageBucket(clazz, null);

	    // 1) List all keys under the prefix
	    final java.util.ArrayList<String> keys = new java.util.ArrayList<>();
	    String continuationToken = null;
	    do {
	        var reqBuilder = ListObjectsV2Request.builder()
	            .bucket(BUCKET_NAME)
	            .prefix(prefix)
	            .maxKeys(1000);

	        if (continuationToken != null) reqBuilder = reqBuilder.continuationToken(continuationToken);

	        var resp = getClient().listObjectsV2(reqBuilder.build());
	        for (var obj : resp.contents()) {
	            keys.add(obj.key());
	        }
	        continuationToken = resp.nextContinuationToken();
	    } while (continuationToken != null);

	    if (keys.isEmpty()) return List.of();

	    // 2) Load objects
	    final var all = new java.util.ArrayList<T>(keys.size());
	    for (var s3Key : keys) {
	        var getReq = GetObjectRequest.builder()
	            .bucket(BUCKET_NAME)
	            .key(s3Key)
	            .build();

	        @Cleanup var in = getClient().getObject(getReq);
	        var obj = PoliscoreUtil.getObjectMapper().readValue(in, clazz);
	        all.add(obj);
	    }

	    // 3) Build comparator: by sortKey if provided, else by Persistable#getId()
	    java.util.Comparator<T> cmp;
	    if (sortKey != null && !sortKey.isBlank()) {
	        cmp = java.util.Comparator.comparing(
	            (T o) -> readProperty(o, sortKey),
	            java.util.Comparator.nullsFirst(S3PersistenceService::compareObjects)
	        );
	    } else {
	        cmp = java.util.Comparator.comparing(
	            (T o) -> ((Persistable) o).getId(),
	            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())
	        );
	    }
	    if (!ascending) cmp = cmp.reversed();

	    // 4) Sort
	    all.sort(cmp);

	    // 5) Cursor: advance past startKey (by id)
	    int startIdx = 0;
	    if (startKey != null && !startKey.isBlank()) {
	        for (int i = 0; i < all.size(); i++) {
	            var p = (Persistable) all.get(i);
	            if (startKey.equals(p.getId())) {
	                startIdx = i + 1;
	                break;
	            }
	        }
	        if (startIdx >= all.size()) return List.of();
	    }

	    // 6) Page
	    int limit = pageSize > 0 ? pageSize : (all.size() - startIdx);
	    int endIdx = Math.min(all.size(), startIdx + limit);
	    return all.subList(startIdx, endIdx);
	}

	/** Read a property via getter or field; may return any Object (possibly null). */
	@SneakyThrows
	private static Object readProperty(Object target, String name) {
	    String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);

	    // Try getters first
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
