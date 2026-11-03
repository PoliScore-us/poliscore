package us.poliscore.service.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import jakarta.enterprise.inject.Instance;
import us.poliscore.model.Persistable;
import us.poliscore.model.referendum.Referendum;
import us.poliscore.service.storage.repository.PostgresPersistableRepository;

class PostgresExtensionRepositoryTest {
    @Test @SuppressWarnings("unchecked")
    void referendumUsesExistingPersistenceInterfaceForWritesReadsQueriesAndCounts() {
        var saved = new HashMap<String, Referendum>();
        var repository = new PostgresPersistableRepository<Referendum>() {
            public Class<Referendum> getPersistableClass() { return Referendum.class; }
            public void put(Referendum r) { saved.put(r.getId(), r); }
            public Optional<Referendum> get(String id) { return Optional.ofNullable(saved.get(id)); }
            public boolean exists(String id) { return saved.containsKey(id); }
            public long count() { return saved.size(); }
            public List<Referendum> query(int size, String index, Boolean ascending, String cursor, String sort, String bucket) { return new ArrayList<>(saved.values()); }
        };
        var store = new PostgresPersistenceService();
        store.extensionRepositories = (Instance<PostgresPersistableRepository<? extends Persistable>>) Proxy.newProxyInstance(
            Instance.class.getClassLoader(), new Class<?>[]{Instance.class}, (proxy, method, args) -> {
                if (method.getName().equals("iterator")) return List.of(repository).iterator();
                throw new UnsupportedOperationException(method.getName());
            });
        var value = new Referendum(); value.setId("REF/us/co/2026-11-03/state/amendment-81");
        store.put(value); store.putIfLatest(value);
        assertTrue(store.exists(value.getId(), Referendum.class)); assertSame(value, store.get(value.getId(), Referendum.class).orElseThrow());
        assertEquals(1, store.count(Referendum.class)); assertEquals(1, store.query(Referendum.class).size());
    }
}
