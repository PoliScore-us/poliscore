package us.poliscore.service.storage;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.Persistable;
import us.poliscore.service.MemoryObjectService;
import us.poliscore.service.storage.LocalS3CacheManifestStore.CacheEntryMetadata;
import us.poliscore.service.storage.S3PersistenceService.QueryCriteria;

@ApplicationScoped
@DefaultBean
public class LocalCachedS3Service extends S3PersistenceService implements ApplicationDataStoreIF
{
	@Inject
	private MemoryObjectService memory;
	
	@Inject
	private LocalFilePersistenceService local;

	@Inject
	private LocalS3CacheManifestStore manifestStore;

	private final Map<String, ManifestState> manifests = new ConcurrentHashMap<>();

	@Override
	public void put(Persistable obj) {
		val fingerprint = writeRemote(obj);
		local.put(obj);
		recordCachedFile(obj.getId(), obj.getClass(), fingerprint);
		memory.put(obj);
	}

	@Override
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		val remoteFingerprint = readRemoteFingerprint(id, clazz);
		if (remoteFingerprint.isEmpty()) {
			invalidate(id, clazz, true);
			return Optional.empty();
		}

		String storageBucket = storageBucket(id, clazz);
		ManifestState manifest = manifest(storageBucket);
		CacheEntryMetadata cachedMetadata = manifest.entries.get(id);

		if (matches(cachedMetadata, remoteFingerprint.get()) && cachedFileMatches(id, cachedMetadata)) {
			if (memory.exists(id, clazz)) return memory.get(id, clazz);

			val cached = readLocal(id, clazz);
			cached.ifPresent(memory::put);
			if (cached.isPresent()) return cached;
			invalidate(id, clazz, true);
		}

		memory.remove(id);

		// Existing installations predate manifests. Adopt a legacy file only when its
		// canonical compact JSON exactly matches S3's single-part ETag.
		if (cachedMetadata == null && local.exists(id, clazz) && legacyFileMatches(id, remoteFingerprint.get())) {
			val cached = readLocal(id, clazz);
			if (cached.isPresent()) {
				recordCachedFile(id, clazz, remoteFingerprint.get());
				memory.put(cached.get());
				return cached;
			}
			invalidate(id, clazz, true);
		}

		val remote = readRemote(id, clazz);
		if (remote.isEmpty()) {
			invalidate(id, clazz, true);
			return Optional.empty();
		}

		local.put(remote.get().object());
		recordCachedFile(id, clazz, remote.get().fingerprint());
		memory.put(remote.get().object());
		return Optional.of(remote.get().object());
	}
	
	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz)
	{
		boolean exists = readRemoteFingerprint(id, clazz).isPresent();
		if (!exists) invalidate(id, clazz, true);
		return exists;
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
		reconcile(Persistable.getClassStorageBucket(clazz, sessionKey));
	}
	
	public <T extends Persistable> void clearExistsOptimize(Class<T> clazz, String sessionKey) {
		String storageBucket = Persistable.getClassStorageBucket(clazz, sessionKey);
		flush(storageBucket);
		manifests.remove(storageBucket);
		super.clearExistsOptimize(clazz, sessionKey);
	}
	
	public <T extends Persistable> void delete(String id, Class<T> clazz)
	{
		super.delete(id, clazz);
		local.delete(id, clazz);
		memory.remove(id);
		removeManifestEntry(id, clazz);
	}

	@PreDestroy
	void flushManifests() {
		for (String storageBucket : List.copyOf(manifests.keySet())) flush(storageBucket);
	}

	private <T extends Persistable> String storageBucket(String id, Class<T> clazz) {
		return Persistable.getClassStorageBucket(clazz, getSessionKey(id));
	}

	protected S3ObjectFingerprint writeRemote(Persistable obj) {
		return super.putWithFingerprint(obj);
	}

	protected <T extends Persistable> Optional<S3ObjectFingerprint> readRemoteFingerprint(String id, Class<T> clazz) {
		return super.getRemoteFingerprint(id, clazz);
	}

	protected <T extends Persistable> Optional<S3GetResult<T>> readRemote(String id, Class<T> clazz) {
		return super.getWithFingerprint(id, clazz);
	}

	private ManifestState manifest(String storageBucket) {
		return manifests.computeIfAbsent(storageBucket,
				ignored -> new ManifestState(manifestStore.load(storageBucket)));
	}

	private void reconcile(String storageBucket) {
		if (!super.hasOptimizedFingerprints(storageBucket)) return;

		ManifestState manifest = manifest(storageBucket);
		Map<String, S3ObjectFingerprint> remote = super.getOptimizedFingerprints(storageBucket);
		for (var entry : List.copyOf(manifest.entries.entrySet())) {
			S3ObjectFingerprint fingerprint = remote.get(entry.getKey());
			if (fingerprint == null) {
				manifest.entries.remove(entry.getKey());
				manifest.dirty.set(true);
				memory.remove(entry.getKey());
				local.delete(entry.getKey(), Persistable.class);
			} else if (fingerprint.eTag() == null || !fingerprint.eTag().equals(entry.getValue().eTag())) {
				memory.remove(entry.getKey());
			}
		}
	}

	private boolean matches(CacheEntryMetadata cached, S3ObjectFingerprint remote) {
		return cached != null && remote.eTag() != null && remote.eTag().equals(cached.eTag());
	}

	private <T extends Persistable> Optional<T> readLocal(String id, Class<T> clazz) {
		try {
			return local.get(id, clazz);
		} catch (Exception unreadable) {
			return Optional.empty();
		}
	}

	private boolean cachedFileMatches(String id, CacheEntryMetadata metadata) {
		try {
			File file = local.fileFor(id);
			return file.exists()
					&& Files.size(file.toPath()) == metadata.localSize()
					&& Files.getLastModifiedTime(file.toPath()).toMillis() == metadata.localModifiedMillis();
		} catch (Exception unreadable) {
			return false;
		}
	}

	private boolean legacyFileMatches(String id, S3ObjectFingerprint remote) {
		if (remote.eTag() == null || !remote.eTag().matches("(?i)[0-9a-f]{32}")) return false;

		File file = local.fileFor(id);
		if (!file.exists()) return false;

		try {
			val tree = PoliscoreUtil.getObjectMapper().readTree(file);
			byte[] compactJson = PoliscoreUtil.getObjectMapper().writeValueAsBytes(tree);
			String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(compactJson));
			return digest.equalsIgnoreCase(remote.eTag());
		} catch (Exception unreadable) {
			return false;
		}
	}

	@SneakyThrows
	private void recordCachedFile(String id, Class<?> clazz, S3ObjectFingerprint fingerprint) {
		File file = local.fileFor(id);
		CacheEntryMetadata metadata = new CacheEntryMetadata(
				fingerprint.eTag(),
				Files.size(file.toPath()),
				Files.getLastModifiedTime(file.toPath()).toMillis());
		ManifestState manifest = manifest(Persistable.getClassStorageBucket(clazz, getSessionKey(id)));
		manifest.entries.put(id, metadata);
		manifest.dirty.set(true);
	}

	private <T extends Persistable> void invalidate(String id, Class<T> clazz, boolean removeDiskFile) {
		memory.remove(id);
		removeManifestEntry(id, clazz);
		if (removeDiskFile) local.delete(id, clazz);
	}

	private <T extends Persistable> void removeManifestEntry(String id, Class<T> clazz) {
		ManifestState manifest = manifest(storageBucket(id, clazz));
		if (manifest.entries.remove(id) != null) manifest.dirty.set(true);
	}

	private void flush(String storageBucket) {
		ManifestState manifest = manifests.get(storageBucket);
		if (manifest == null || !manifest.dirty.compareAndSet(true, false)) return;

		try {
			manifestStore.save(storageBucket, Map.copyOf(manifest.entries));
		} catch (RuntimeException failure) {
			manifest.dirty.set(true);
			throw failure;
		}
	}

	private static final class ManifestState {
		private final Map<String, CacheEntryMetadata> entries;
		private final AtomicBoolean dirty = new AtomicBoolean(false);

		private ManifestState(Map<String, CacheEntryMetadata> entries) {
			this.entries = new ConcurrentHashMap<>(entries);
		}
	}
	
}
