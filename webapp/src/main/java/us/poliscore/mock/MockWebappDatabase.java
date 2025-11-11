package us.poliscore.mock;

import java.util.List;
import java.util.Optional;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Synchronized;
import us.poliscore.PoliscoreDataset;
import us.poliscore.billing.UserAccount;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.Persistable;
import us.poliscore.service.storage.ObjectStorageServiceIF;

/**
 * MockWebappDatabase
 * 
 * A lightweight, in-memory implementation of ObjectStorageServiceIF that
 * constructs and caches a mock PoliscoreDataset via MockDatasetUtil.
 * All ObjectStorageServiceIF methods delegate directly to that dataset.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class MockWebappDatabase implements ObjectStorageServiceIF {

    private volatile PoliscoreDatasetIF dataset;

    private PoliscoreDatasetIF ensureDataset() {
        if (dataset == null) {
            synchronized (this) {
                if (dataset == null) {
                	MockDatasetProvider provider = new MockDatasetProvider();
                	dataset = provider.importDataset(MockDatasetUtil.mockDeployment());
                }
            }
        }
        return dataset;
    }
    
    protected void injectMockUser(PoliscoreDataset dataset) {
    	UserAccount ua = new UserAccount();
    	ua.setId("l2.rowlands@gmail.com");
    	ua.setPlan("premium");
    	ua.setStatus("active");
    }

    @Override
    public <T extends Persistable> Optional<T> get(String id, Class<T> clazz) {
        return ensureDataset().get(id, clazz);
    }

    @Override
    public <T extends Persistable> void put(T obj) {
        ensureDataset().put(obj);
    }

    @Override
    public <T extends Persistable> boolean exists(String id, Class<T> clazz) {
        return ensureDataset().exists(id, clazz);
    }

    @Override
    public <T extends Persistable> List<T> query(Class<T> clazz) {
        return ensureDataset().query(clazz);
    }

    @Override
    public <T extends Persistable> List<T> query(
            Class<T> clazz,
            int pageSize,
            String index,
            Boolean ascending,
            String startKey,
            String sortKey,
            String storageBucket) {
        return ensureDataset().query(clazz, pageSize, index, ascending, startKey, sortKey, storageBucket);
    }

    @Override
    public <T extends Persistable> long count(Class<T> clazz) {
        return ensureDataset().count(clazz);
    }

    /** Resets the cached dataset (for testing). */
    @Synchronized
    public void reset() {
        dataset = null;
    }

    /** Returns the underlying mock dataset, lazily initialized. */
    public PoliscoreDatasetIF getDataset() {
        return ensureDataset();
    }

    /** Returns the underlying DeploymentConfig used for the mock dataset. */
    public PoliscoreDataset.DeploymentConfig getDeploymentConfig() {
        return MockDatasetUtil.mockDeployment();
    }
}
