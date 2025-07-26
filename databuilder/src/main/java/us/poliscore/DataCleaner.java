package us.poliscore;

import java.io.IOException;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.CongressionalBillType;
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
		
//		val co = data.getDataset(LegislativeNamespace.US_COLORADO, 2025);
//		wipeAllBills(co);
		
		val congress = data.getDataset(LegislativeNamespace.US_CONGRESS, 2026);
		
		val obbb = congress.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", CongressionalBillType.HR, 1), Bill.class).get();
		
		val interp = s3.get(BillInterpretation.generateId(obbb.getId(), null), BillInterpretation.class).get();
		
		System.out.println(interp.getGenBillTitle());
		
		System.out.println("Program complete.");
	}
	
	public void wipeAllLegislators() {
//		for (val leg : ddb.query(Legislator.class)) {
//			ddb.delete(leg);
//		}
	}
	
	public void wipeAllBills(PoliscoreDataset dataset) {
		for (val bill : ddb.query(Bill.class, dataset.getSession().getKey())) {
			ddb.delete(bill);
		}
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
