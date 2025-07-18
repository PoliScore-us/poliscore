package us.poliscore;

import java.io.IOException;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.service.BillInterpretationService;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.storage.DynamoDbPersistenceService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name="DataCleaner")
public class DataCleaner implements QuarkusApplication {
	
	@Inject private LegislatorService legService;
	
	@Inject private BillService billService;
	
	@Inject
	private BillInterpretationService billInterpreter;
	
	@Inject private GovernmentDataService data;
	
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	private DynamoDbPersistenceService ddb;
	
	protected void process() throws IOException
	{
		data.importAllDatasets();
		
		wipeAllLegislators();
		
		System.out.println("Program complete.");
	}
	
	public void wipeAllLegislators() {
//		for (val leg : ddb.query(Legislator.class)) {
//			ddb.delete(leg);
//		}
	}
	
	@Override
	public int run(String... args) throws Exception {
	  process();
	  
	  Quarkus.waitForExit();
	  return 0;
	}
	
	public static void main(String[] args) {
		Quarkus.run(DataCleaner.class, args);
		Quarkus.asyncExit(0);
	}
}
