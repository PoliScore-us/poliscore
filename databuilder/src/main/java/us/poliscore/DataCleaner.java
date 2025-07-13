package us.poliscore;

import java.io.IOException;
import java.util.stream.Collectors;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.model.IssueStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
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
		data.importDatasets();
		
		s3.optimizeExists(BillInterpretation.class);
		
		
		System.out.println("Program complete.");
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
