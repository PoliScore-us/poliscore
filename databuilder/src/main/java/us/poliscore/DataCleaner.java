package us.poliscore;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.service.BillInterpretationService;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.storage.DynamoDbPersistenceService;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.S3PersistenceService.QueryCriteria;

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
		val dataset = data.importDataset(LegislativeNamespace.US_CONGRESS, 2026);
		
//		cleanInvalidPressInterps(dataset);
//		wipeAllPressInterps(dataset.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 2023), Bill.class).get());
		
		redeployBill(dataset.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 2023), Bill.class).get());
		redeployBill(dataset.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 2027), Bill.class).get());
		redeployBill(dataset.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 2038), Bill.class).get());
		redeployBill(dataset.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 2221), Bill.class).get());
		redeployBill(dataset.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 2257), Bill.class).get());
		redeployBill(dataset.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 2266), Bill.class).get());
		redeployBill(dataset.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 2291), Bill.class).get());
		redeployBill(dataset.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 2292), Bill.class).get());
		redeployBill(dataset.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 2309), Bill.class).get());
		
		
		System.out.println("Program complete.");
	}
	
	public void redeployBill(Bill b) {
		val interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).get();
	    billService.ddbPersist(b, interp);
	    Log.info("Redeployed bill " + b.getId() + " to ddb");
	}
	
//	public void wipeAllLegislators() {
//		for (val leg : ddb.query(Legislator.class)) {
//			ddb.delete(leg);
//		}
//	}
	
//	public void wipeAllBills(PoliscoreDataset dataset) {
//		for (val bill : ddb.query(Bill.class, dataset.getSession().getKey())) {
//			ddb.delete(bill);
//		}
//	}
	
	public void wipeAllPressInterps(Bill b)
	{
		var pressInterps = billService.getPressInterps(b.getId(), false);
		
		for (val interp : pressInterps)
		{
			s3.delete(interp.getId(), PressInterpretation.class);
		}
		
		Log.info("Deleted " + pressInterps.size() + " existing interpretations");
	}
	
	public void cleanInvalidPressInterps(PoliscoreDataset dataset) {
		Log.info("Verifying reachability for S3 press interpretations.");
		
		int total = 0;
		int deleted = 0;
		
		for (val pi : s3.query(PressInterpretation.class, dataset.getSession().getKey(), new QueryCriteria().setLastModifiedAfter(Instant.now().minus(5, ChronoUnit.DAYS)))) {
			try {
				val origin = pi.getOrigin();
				
				if (origin.getIdHash().equals("unknown")) {
					throw new RuntimeException("Invalid interpretation origin.");
				}
				
				origin.validate(dataset.get(pi.getBillId(), Bill.class).get().getOfficialUrl());
			} catch (Exception e) {
				s3.delete(pi.getId(), PressInterpretation.class);
				deleted++;
			}
			
			total++;
		}
		
		Log.info("Finished cleaning up press interps. Deleted " + deleted + " of total " + total);
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
