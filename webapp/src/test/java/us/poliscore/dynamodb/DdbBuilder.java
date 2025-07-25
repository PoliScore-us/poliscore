package us.poliscore.dynamodb;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.MemoryObjectService;
import us.poliscore.service.storage.DynamoDbPersistenceService;

@ApplicationScoped
public class DdbBuilder
{
	
	@Inject
    DynamoDbClient dbc;
	
	@Inject
	private LegislatorService legService;
	
	@Inject
	private MemoryObjectService memory;
	
	@Inject
	private DynamoDbPersistenceService ddb;
	
	private boolean bootstrapped = false;
	
	public void defaultTestData()
	{
		if (!bootstrapped)
		{
//			createTable();
			
//			legService.importLegislators();
			
//			ddb.put(memory.get(TestUtils.BERNIE_SANDERS_ID, Legislator.class).orElseThrow());
			
			bootstrapped = true;
		}
	}
	
//	public void createTable()
//	{
//		dbc.createTable(CreateTableRequest.builder()
//				.tableName(DynamoDbPersistenceService.TABLE_NAME)
//				.keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
//				.attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType("S").build())
//				.provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(5L).writeCapacityUnits(5L).build())
//				.build());
//		
//		// TODO : This causes an unspecified error thrown from ddb localstack
////		createIndex("ObjectsByLocation",
////				Arrays.asList(
////						AttributeDefinition.builder().attributeName("idClassPrefix").attributeType(ScalarAttributeType.S).build(),
////						AttributeDefinition.builder().attributeName("location").attributeType(ScalarAttributeType.S).build()
////				),
////				KeySchemaElement.builder().keyType(KeyType.HASH).attributeName("idClassPrefix").build(),
////				KeySchemaElement.builder().keyType(KeyType.RANGE).attributeName("location").build());
//	}
	
//	private void createIndex(String name, Collection<AttributeDefinition> attrDefs, KeySchemaElement ...schema) {
//		CreateGlobalSecondaryIndexAction action = CreateGlobalSecondaryIndexAction
//                .builder()
//                .indexName(name)
//                .keySchema(schema)
//                .build();
//		GlobalSecondaryIndexUpdate index = GlobalSecondaryIndexUpdate
//		                .builder()
//		                .create(action)
//		                .build();
//		UpdateTableRequest request = UpdateTableRequest
//		                .builder()
//		                .tableName(DynamoDbPersistenceService.TABLE_NAME)
//		                .globalSecondaryIndexUpdates(index)
//		                .attributeDefinitions(attrDefs)
//		                .build();
//		dbc.updateTable(request);
//	}
	
}
