package us.poliscore.mock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import us.poliscore.WebappDatabase;
import us.poliscore.service.storage.DynamoDbPersistenceService;
import us.poliscore.service.storage.ObjectStorageServiceIF;

@ApplicationScoped
public class WebappDatabaseProducer {

  @Inject DynamoDbPersistenceService dynamo;

  // Only resolvable in dev because the bean is dev-scoped
  @Inject jakarta.enterprise.inject.Instance<MockWebappDatabase> mock;

  @Produces
  @WebappDatabase
  public ObjectStorageServiceIF webappDatabase() {
    return mock.isResolvable() ? mock.get() : dynamo;
  }
}

