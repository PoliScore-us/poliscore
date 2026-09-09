package us.poliscore.service.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import us.poliscore.model.Persistable;

class MemoryObjectStoreSessionTest {

	@Test
	void clearsOnlyObjectsFromRequestedSessions() {
		var store = new MemoryObjectStore();
		var current = new TestPersistable("OBJ/us/co/2243/current");
		var special = new TestPersistable("OBJ/us/co/2299/special");
		var other = new TestPersistable("OBJ/us/az/2235/other");
		store.put(current);
		store.put(special);
		store.put(other);

		store.clearSessions(Set.of("us/co/2243", "us/co/2299"));

		assertFalse(store.exists(current.getId(), TestPersistable.class));
		assertFalse(store.exists(special.getId(), TestPersistable.class));
		assertTrue(store.exists(other.getId(), TestPersistable.class));
	}

	private static final class TestPersistable implements Persistable {
		private String id;

		private TestPersistable(String id) { this.id = id; }
		@Override public String getId() { return id; }
		@Override public void setId(String id) { this.id = id; }
		@Override public String getStorageBucket() { return "OBJ"; }
		@Override public void setStorageBucket(String prefix) { }
	}
}
