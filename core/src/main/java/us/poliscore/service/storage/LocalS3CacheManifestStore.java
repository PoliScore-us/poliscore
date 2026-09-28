package us.poliscore.service.storage;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.SneakyThrows;
import us.poliscore.PoliscoreUtil;

@ApplicationScoped
public class LocalS3CacheManifestStore {

	private static final TypeReference<Map<String, CacheEntryMetadata>> MANIFEST_TYPE = new TypeReference<>() { };

	protected File manifestRoot() {
		return new File(new File(PoliscoreUtil.appData(), "store"), ".s3-cache-index");
	}

	protected File manifestFile(String storageBucket) {
		return new File(manifestRoot(), storageBucket + ".json");
	}

	@SneakyThrows
	public Map<String, CacheEntryMetadata> load(String storageBucket) {
		File file = manifestFile(storageBucket);
		if (!file.exists()) return new HashMap<>();

		try {
			return new HashMap<>(PoliscoreUtil.getObjectMapper().readValue(file, MANIFEST_TYPE));
		} catch (Exception malformed) {
			// Cache metadata is disposable. Treat a malformed manifest as a cold cache.
			return new HashMap<>();
		}
	}

	@SneakyThrows
	public void save(String storageBucket, Map<String, CacheEntryMetadata> entries) {
		File target = manifestFile(storageBucket);
		File parent = target.getParentFile();
		if (parent != null) parent.mkdirs();

		File temporary = Files.createTempFile(parent.toPath(), target.getName(), ".tmp").toFile();
		try {
			PoliscoreUtil.getObjectMapper().writerWithDefaultPrettyPrinter().writeValue(temporary, entries);
			try {
				Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary.toPath());
		}
	}

	public record CacheEntryMetadata(String eTag, long localSize, long localModifiedMillis) { }
}
