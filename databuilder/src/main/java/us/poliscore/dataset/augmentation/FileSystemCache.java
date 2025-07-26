package us.poliscore.dataset.augmentation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class FileSystemCache<T> {

    private static final Logger LOGGER = Logger.getLogger(FileSystemCache.class.getName());

    private final File baseDir;
    private final ObjectMapper objectMapper;
    private final int defaultTtlSecs;

    public FileSystemCache(File baseDir, ObjectMapper objectMapper, int defaultTtlSecs) {
        this.baseDir = baseDir;
        this.objectMapper = objectMapper;
        this.defaultTtlSecs = defaultTtlSecs;

        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("Could not create cache directory: " + baseDir);
        }
    }

    public FileSystemCache(File baseDir, ObjectMapper objectMapper) {
        this(baseDir, objectMapper, 0);
    }

    private File resolvePath(String key) {
        String filename = key.replaceAll("[^/a-zA-Z0-9\\-_]", "_") + "/cached.json";
        return new File(baseDir, filename);
    }

    public <U extends T> Optional<U> getOrExpire(String key, TypeReference<U> typeRef) {
        File file = resolvePath(key);
        if (!file.exists()) return Optional.empty();

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            CachedEntry entry = objectMapper.readValue(data, CachedEntry.class);

            if (entry.isExpired()) {
                LOGGER.fine("Cache expired for key: " + key);
                file.delete();
                return Optional.empty();
            }

            U value = objectMapper.convertValue(entry.getValue(), typeRef);
            return Optional.of(value);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to read cache for key: " + key, e);
            return Optional.empty();
        }
    }

    public <U extends T> Optional<U> peek(String key, TypeReference<U> typeRef) {
        File file = resolvePath(key);
        if (!file.exists()) return Optional.empty();

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            CachedEntry entry = objectMapper.readValue(data, CachedEntry.class);

            U value = objectMapper.convertValue(entry.getValue(), typeRef);
            return Optional.of(value);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to read cache for key: " + key, e);
            return Optional.empty();
        }
    }
    
    public Optional<CachedEntry> peekEntry(String key) {
        File file = resolvePath(key);
        if (!file.exists()) return Optional.empty();

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            CachedEntry entry = objectMapper.readValue(data, CachedEntry.class);
            return Optional.of(entry);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to read cache entry for key: " + key, e);
            return Optional.empty();
        }
    }

    public void put(String key, T value) {
        put(key, value, defaultTtlSecs);
    }

    public void put(String key, T value, long ttlSecs) {
        File file = resolvePath(key);
        file.getParentFile().mkdirs();
        try {
            CachedEntry entry = new CachedEntry(value, Instant.now().getEpochSecond(), ttlSecs);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, entry);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to write cache for key: " + key, e);
        }
    }

    public void remove(String key) {
        File file = resolvePath(key);
        if (file.exists()) {
            try {
                Files.delete(file.toPath());
                LOGGER.fine("Cache file deleted for key: " + key);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to delete cache file for key: " + key, e);
            }
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CachedEntry {
        private Object value;
        private long timestamp;
        private long ttl;

        @JsonIgnore
        public boolean isExpired() {
            return ttl > 0 && Instant.now().getEpochSecond() > (timestamp + ttl);
        }
    }
}
