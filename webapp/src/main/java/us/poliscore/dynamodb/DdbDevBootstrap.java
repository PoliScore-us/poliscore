package us.poliscore.dynamodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateGlobalSecondaryIndexAction;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexUpdate;
import software.amazon.awssdk.services.dynamodb.model.IndexStatus;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;
import us.poliscore.service.MemoryObjectService;
import us.poliscore.service.storage.DynamoDbPersistenceService;

/**
 * Was used for a minute when we were running ddb in 'quarkus dev'. Now we just mock the db with "MockWebappDatabase"
 */
//@ApplicationScoped
//@Startup
//@IfBuildProfile("dev")
@JBossLog
public class DdbDevBootstrap {

//    @Inject DynamoDbClient dbc;
//
////    @Inject LegislatorService legService;
//    @Inject MemoryObjectService memory;
//    @Inject DynamoDbPersistenceService ddb;
//
//    private volatile boolean bootstrapped = false;
//
//    // ---- customize these to your schema ----
//    private static final String PK = "id";                                     // your partition key
//    private static final ScalarAttributeType PK_TYPE = ScalarAttributeType.S;
//
//    // Example GSI you attempted: ObjectsByLocation (HASH=idClassPrefix, RANGE=location)
//    private static final String GSI1 = "ObjectsByLocation";
//    private static final String GSI1_HASH = "idClassPrefix";
//    private static final String GSI1_RANGE = "location";
//    // ----------------------------------------
//
//    public void onStart(@Observes StartupEvent ev) {
//        if (bootstrapped) return;
//        try {
//            ensureTable();
//            ensureGsiObjectsByLocation();
//
//            // Optional: seed once (guarded by "is table empty?" check)
//            seedOnce();
//
//            bootstrapped = true;
//            log.info("DynamoDB dev bootstrap complete.");
//        } catch (Exception e) {
//            log.error("DynamoDB dev bootstrap failed", e);
//        }
//    }
//
//    private void ensureTable() {
//        try {
//            dbc.describeTable(b -> b.tableName(ddb.getTableName()));
//            log.infof("Table '%s' already exists", ddb.getTableName());
//        } catch (ResourceNotFoundException rnfe) {
//            log.infof("Creating table '%s'…", ddb.getTableName());
//
//            // Base attributes: table keys (+ anything your first GSIs need if you prefer).
//            List<AttributeDefinition> attrDefs = new ArrayList<>();
//            attrDefs.add(AttributeDefinition.builder().attributeName(PK).attributeType(PK_TYPE).build());
//
//            // If your table has a sort key, add it here (example):
//            // attrDefs.add(AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build());
//            // and add to keySchema below.
//
//            List<KeySchemaElement> keySchema = new ArrayList<>();
//            keySchema.add(KeySchemaElement.builder().attributeName(PK).keyType(KeyType.HASH).build());
//            // keySchema.add(KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build());
//
//            CreateTableRequest req = CreateTableRequest.builder()
//                    .tableName(ddb.getTableName())
//                    .attributeDefinitions(attrDefs)
//                    .keySchema(keySchema)
//                    // For dev, on-demand keeps life simple
//                    .billingMode(BillingMode.PAY_PER_REQUEST)
//                    .build();
//
//            dbc.createTable(req);
//            waitForTableActive(ddb.getTableName());
//            log.infof("Created table '%s'.", ddb.getTableName());
//        }
//    }
//
//    private void ensureGsiObjectsByLocation() {
//        TableDescription td = dbc.describeTable(b -> b.tableName(ddb.getTableName())).table();
//
//        boolean exists = Optional.ofNullable(td.globalSecondaryIndexes())
//                .orElse(List.of())
//                .stream()
//                .anyMatch(i -> GSI1.equals(i.indexName()));
//
//        if (exists) {
//            log.infof("GSI '%s' already exists", GSI1);
//            return;
//        }
//
//        log.infof("Creating GSI '%s' on table '%s'…", GSI1, ddb.getTableName());
//
//        // BUGFIX #1: Include Projection + (for LocalStack) ProvisionedThroughput OR use PAY_PER_REQUEST.
//        // UpdateTable does not let you set billing mode for GSI alone; keep table on-demand or supply throughput here.
//        CreateGlobalSecondaryIndexAction create = CreateGlobalSecondaryIndexAction.builder()
//                .indexName(GSI1)
//                .keySchema(
//                        KeySchemaElement.builder().attributeName(GSI1_HASH).keyType(KeyType.HASH).build(),
//                        KeySchemaElement.builder().attributeName(GSI1_RANGE).keyType(KeyType.RANGE).build()
//                )
//                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
//                // Provisioned is accepted by both DynamoDB Local and LocalStack even if the table is on-demand
//                .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(5L).writeCapacityUnits(5L).build())
//                .build();
//
//        GlobalSecondaryIndexUpdate gsiUpdate = GlobalSecondaryIndexUpdate.builder()
//                .create(create)
//                .build();
//
//        // BUGFIX #2: Supply NEW attribute definitions for attributes used by the new GSI
//        List<AttributeDefinition> newAttrs = List.of(
//                AttributeDefinition.builder().attributeName(GSI1_HASH).attributeType(ScalarAttributeType.S).build(),
//                AttributeDefinition.builder().attributeName(GSI1_RANGE).attributeType(ScalarAttributeType.S).build()
//        );
//
//        // Merge with any existing attr defs known by the table (AWS ignores duplicates by name)
//        Set<String> existingAttrNames = Optional.ofNullable(td.attributeDefinitions())
//                .orElse(List.of())
//                .stream().map(AttributeDefinition::attributeName).collect(Collectors.toSet());
//
//        List<AttributeDefinition> merged = new ArrayList<>(Optional.ofNullable(td.attributeDefinitions()).orElse(List.of()));
//        newAttrs.forEach(a -> { if (!existingAttrNames.contains(a.attributeName())) merged.add(a); });
//
//        UpdateTableRequest update = UpdateTableRequest.builder()
//                .tableName(ddb.getTableName())
//                .globalSecondaryIndexUpdates(gsiUpdate)
//                .attributeDefinitions(merged)
//                .build();
//
//        dbc.updateTable(update);
//        waitForIndexActive(ddb.getTableName(), GSI1);
//        log.infof("Created GSI '%s'.", GSI1);
//    }
//
//    private void seedOnce() {
//        // Only seed if empty
//        ScanResponse scan = dbc.scan(b -> b.tableName(ddb.getTableName()).limit(1));
//        if (scan.count() > 0) {
//            log.info("Seed skipped (table already has items).");
//            return;
//        }
//
//        log.info("Seeding sample data…");
//        // Example: call your existing services (uncomment what you had)
//        // legService.importLegislators();
//        // ddb.put(memory.get(TestUtils.BERNIE_SANDERS_ID, Legislator.class).orElseThrow());
//        log.info("Seed complete.");
//    }
//
//    private void waitForTableActive(String tableName) {
//        // Simple waiter loop—fine for dev
//        for (;;) {
//            DescribeTableResponse dtr = dbc.describeTable(b -> b.tableName(tableName));
//            String status = dtr.table().tableStatusAsString();
//            if ("ACTIVE".equals(status)) return;
//            sleep(350);
//        }
//    }
//
//    private void waitForIndexActive(String tableName, String indexName) {
//        for (;;) {
//            DescribeTableResponse dtr = dbc.describeTable(b -> b.tableName(tableName));
//            boolean active = Optional.ofNullable(dtr.table().globalSecondaryIndexes())
//                    .orElse(List.of())
//                    .stream()
//                    .filter(i -> indexName.equals(i.indexName()))
//                    .allMatch(i -> i.indexStatus() == IndexStatus.ACTIVE);
//            if (active) return;
//            sleep(350);
//        }
//    }
//
//    private static void sleep(long ms) {
//        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
//    }
}
