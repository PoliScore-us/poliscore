package us.poliscore.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.poliscore.PoliscoreUtil;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;
import us.poliscore.model.bill.BillTextPublishVersion;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.service.MemoryObjectService;
import us.poliscore.service.storage.LocalS3CacheManifestStore.CacheEntryMetadata;

class LocalCachedS3ServiceTest {

	@TempDir
	Path temporaryDirectory;

	private static final AtomicInteger BILL_NUMBER = new AtomicInteger(9000);

	@Test
	void matchingManifestUsesLocalBillTextWithoutRemoteGet() throws Exception {
		BillText cached = billText("cached");
		TestFixture fixture = fixture(cached, cached);
		fixture.writeManifest(cached, etag(cached));

		BillText result = fixture.service.get(cached.getId(), BillText.class).orElseThrow();

		assertEquals("cached", result.getText());
		assertEquals(0, fixture.service.remoteGetCount);
	}

	@Test
	void changedRemoteEtagRefreshesStaleBillText() throws Exception {
		BillText cached = billText("stale");
		BillText remote = replacement(cached, "fresh");
		TestFixture fixture = fixture(cached, remote);
		fixture.writeManifest(cached, etag(cached));
		fixture.memory.put(cached);

		BillText result = fixture.service.get(cached.getId(), BillText.class).orElseThrow();

		assertEquals("fresh", result.getText());
		assertEquals(1, fixture.service.remoteGetCount);
		assertEquals("fresh", fixture.local.get(cached.getId(), BillText.class).orElseThrow().getText());
	}

	@Test
	void remotelyDeletedObjectIsRemovedFromEveryLocalCacheLayer() throws Exception {
		BillText cached = billText("deleted");
		TestFixture fixture = fixture(cached, null);
		fixture.writeManifest(cached, etag(cached));
		fixture.memory.put(cached);

		assertEquals(Optional.empty(), fixture.service.get(cached.getId(), BillText.class));
		assertEquals(false, fixture.local.exists(cached.getId(), BillText.class));
		assertEquals(false, fixture.memory.exists(cached.getId(), BillText.class));
	}

	@Test
	void legacyCacheFileIsAdoptedWhenCanonicalJsonMatchesS3Etag() throws Exception {
		BillText cached = billText("legacy");
		TestFixture fixture = fixture(cached, cached);

		BillText result = fixture.service.get(cached.getId(), BillText.class).orElseThrow();

		assertEquals("legacy", result.getText());
		assertEquals(0, fixture.service.remoteGetCount);
		fixture.service.flushManifests();
		assertEquals(etag(cached), fixture.manifest.load(storageBucket(cached)).get(cached.getId()).eTag());
	}

	private TestFixture fixture(BillText cached, BillText remote) throws Exception {
		File cacheRoot = temporaryDirectory.resolve("store").toFile();
		TestLocalFilePersistenceService local = new TestLocalFilePersistenceService(cacheRoot);
		TestManifestStore manifest = new TestManifestStore(temporaryDirectory.resolve("manifest").toFile());
		MemoryObjectService memory = new MemoryObjectService();
		memory.remove(cached.getId());
		local.put(cached);

		TestLocalCachedS3Service service = new TestLocalCachedS3Service(remote, remote == null ? null : etag(remote));
		inject(service, "local", local);
		inject(service, "memory", memory);
		inject(service, "manifestStore", manifest);
		return new TestFixture(service, local, manifest, memory);
	}

	private void inject(Object target, String fieldName, Object value) throws Exception {
		Field field = LocalCachedS3Service.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private BillText billText(String text) {
		int number = BILL_NUMBER.incrementAndGet();
		String billId = Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", CongressionalBillType.HR, number);
		return BillText.factory(billId, 0, text, LocalDate.of(2026, 1, 1), BillTextPublishVersion.IH, BillTextFormat.XML);
	}

	private BillText replacement(BillText existing, String text) {
		BillText replacement = BillText.factory(existing.getBillId(), 0, text, LocalDate.of(2026, 1, 1), BillTextPublishVersion.IH, BillTextFormat.XML);
		replacement.setId(existing.getId());
		return replacement;
	}

	private String storageBucket(BillText text) {
		return Persistable.getClassStorageBucket(BillText.class, "us/congress/119");
	}

	private String etag(Persistable object) throws Exception {
		byte[] compactJson = PoliscoreUtil.getObjectMapper().writeValueAsBytes(object);
		return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(compactJson));
	}

	private final class TestFixture {
		private final TestLocalCachedS3Service service;
		private final TestLocalFilePersistenceService local;
		private final TestManifestStore manifest;
		private final MemoryObjectService memory;

		private TestFixture(TestLocalCachedS3Service service, TestLocalFilePersistenceService local, TestManifestStore manifest,
				MemoryObjectService memory) {
			this.service = service;
			this.local = local;
			this.manifest = manifest;
			this.memory = memory;
		}

		private void writeManifest(BillText text, String eTag) throws Exception {
			File file = local.fileFor(text.getId());
			manifest.save(storageBucket(text), Map.of(text.getId(), new CacheEntryMetadata(
					eTag,
					Files.size(file.toPath()),
					Files.getLastModifiedTime(file.toPath()).toMillis())));
		}
	}

	private static final class TestLocalCachedS3Service extends LocalCachedS3Service {
		private final Map<String, Persistable> remote = new ConcurrentHashMap<>();
		private final Map<String, S3ObjectFingerprint> fingerprints = new ConcurrentHashMap<>();
		private int remoteGetCount;

		private TestLocalCachedS3Service(Persistable object, String eTag) {
			if (object != null) {
				remote.put(object.getId(), object);
				fingerprints.put(object.getId(), new S3ObjectFingerprint(eTag, 1));
			}
		}

		@Override
		protected <T extends Persistable> Optional<S3ObjectFingerprint> readRemoteFingerprint(String id, Class<T> clazz) {
			return Optional.ofNullable(fingerprints.get(id));
		}

		@Override
		protected <T extends Persistable> Optional<S3GetResult<T>> readRemote(String id, Class<T> clazz) {
			remoteGetCount++;
			Persistable object = remote.get(id);
			return object == null ? Optional.empty() : Optional.of(new S3GetResult<>(clazz.cast(object), fingerprints.get(id)));
		}
	}

	private static final class TestLocalFilePersistenceService extends LocalFilePersistenceService {
		private final File root;

		private TestLocalFilePersistenceService(File root) { this.root = root; }
		@Override protected File getLocalStorage() { return root; }
	}

	private static final class TestManifestStore extends LocalS3CacheManifestStore {
		private final File root;

		private TestManifestStore(File root) { this.root = root; }
		@Override protected File manifestRoot() { return root; }
	}
}
