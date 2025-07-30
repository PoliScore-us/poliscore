package us.poliscore;

import java.io.IOException;
import java.time.LocalDate;

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
	
	public static final String[] ids = new String[] {
			"BIT/us/congress/119/s/2393"
			,"BIT/us/congress/119/s/2392"
			,"BIT/us/congress/119/s/2398"
			,"BIT/us/congress/119/hr/4478"
			,"BIT/us/congress/119/hr/4495"
			,"BIT/us/congress/119/hr/4499"
			,"BIT/us/congress/119/hr/4491"
			,"BIT/us/congress/119/s/2342"
			,"BIT/us/congress/119/s/2354"
			,"BIT/us/congress/119/hr/4544"
			,"BIT/us/congress/119/s/2299"
			,"BIT/us/congress/119/s/2297"
			,"BIT/us/congress/119/hr/4449"
			,"BIT/us/congress/119/hr/4460"
			,"BIT/us/congress/119/hr/4465"
			,"BIT/us/congress/119/hr/4470"
			,"BIT/us/congress/119/hr/4471"
			,"BIT/us/congress/119/hr/4472"
			,"BIT/us/congress/119/hr/4473"
			,"BIT/us/congress/119/hr/4468"
			,"BIT/us/congress/119/hr/4469"
			,"BIT/us/congress/119/hr/4431"
			,"BIT/us/congress/119/hr/4427"
			,"BIT/us/congress/119/hr/4429"
			,"BIT/us/congress/119/hr/4442"
			,"BIT/us/congress/119/hr/4437"
			,"BIT/us/congress/119/s/2301"
			,"BIT/us/congress/119/s/2292"
			,"BIT/us/congress/119/hr/4423"
			,"BIT/us/congress/119/hr/4366"
			,"BIT/us/congress/119/hjres/106"
			,"BIT/us/congress/119/hr/4334"
			,"BIT/us/congress/119/hr/4327"
			,"BIT/us/congress/119/hr/4335"
			,"BIT/us/congress/119/hr/4352"
			,"BIT/us/congress/119/hr/4356"
			,"BIT/us/congress/119/hr/4346"
			,"BIT/us/congress/119/hr/4311"
			,"BIT/us/congress/119/hr/4312"
			,"BIT/us/congress/119/hr/4306"
			,"BIT/us/congress/119/hr/4307"
			,"BIT/us/congress/119/hr/4309"
			,"BIT/us/congress/119/hr/4320"
			,"BIT/us/congress/119/hr/4323"
			,"BIT/us/congress/119/hr/4313"
			,"BIT/us/congress/119/hr/4317"
			,"BIT/us/congress/119/s/2237"
			,"BIT/us/congress/119/s/2251"
			,"BIT/us/congress/119/s/2256"
			,"BIT/us/congress/119/s/2257"
			,"BIT/us/congress/119/sjres/61"
			,"BIT/us/congress/119/hr/4265"
			,"BIT/us/congress/119/hr/4266"
			,"BIT/us/congress/119/sjres/59"
			,"BIT/us/congress/119/s/2003"
			,"BIT/us/congress/119/s/2014"
			,"BIT/us/congress/119/s/2013"
			,"BIT/us/congress/119/s/2012"
			,"BIT/us/congress/119/s/2011"
			,"BIT/us/congress/119/s/2016"
			,"BIT/us/congress/119/s/2019"
			,"BIT/us/congress/119/hr/3880"
			,"BIT/us/congress/119/hr/3873"
			,"BIT/us/congress/119/hr/3874"
			,"BIT/us/congress/119/hr/3878"
			,"BIT/us/congress/119/hr/3879"
			,"BIT/us/congress/119/hr/3890"
			,"BIT/us/congress/119/hr/3892"
			,"BIT/us/congress/119/hr/3894"
			,"BIT/us/congress/119/hr/3884"
			,"BIT/us/congress/119/hr/3885"
			,"BIT/us/congress/119/hr/3886"
			,"BIT/us/congress/119/hr/3856"
			,"BIT/us/congress/119/hr/3857"
			,"BIT/us/congress/119/hr/3863"
			,"BIT/us/congress/119/hr/3864"
			,"BIT/us/congress/119/hr/3867"
			,"BIT/us/congress/119/hr/3548"
			,"BIT/us/congress/119/hr/3565"
			,"BIT/us/congress/119/s/1808"
			,"BIT/us/congress/119/s/1821"
			,"BIT/us/congress/119/s/1802"
			,"BIT/us/congress/119/s/1804"
			,"BIT/us/congress/119/s/1792"
			,"BIT/us/congress/119/s/1797"
			,"BIT/us/congress/119/s/1798"
			,"BIT/us/congress/119/s/1772"
			,"BIT/us/congress/119/s/1773"
			,"BIT/us/congress/119/s/1787"
			,"BIT/us/congress/119/s/1784"
			,"BIT/us/congress/119/s/1785"
			,"BIT/us/congress/119/s/1751"
			,"BIT/us/congress/119/s/1737"
			,"BIT/us/congress/119/s/1743"
			,"BIT/us/congress/119/s/1699"
			,"BIT/us/congress/119/s/1660"
			,"BIT/us/congress/119/s/1604"
	};
	
	protected void process() throws IOException
	{
		data.importAllDatasets();
		
//		val co = data.getDataset(LegislativeNamespace.US_COLORADO, 2025);
//		wipeAllBills(co);
		
//		val congress = data.getDataset(LegislativeNamespace.US_CONGRESS, 2026);
//		
//		val obbb = congress.get(Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", CongressionalBillType.HR, 1), Bill.class).get();
//		
//		val interp = s3.get(BillInterpretation.generateId(obbb.getId(), null), BillInterpretation.class).get();
//		
//		System.out.println(interp.getGenBillTitle());
		
		for (val id : ids) {
			val interp = s3.get(id + "-polisc", BillInterpretation.class).orElse(null);
//			val bill = data.get(id.replace(BillInterpretation.ID_CLASS_PREFIX, Bill.ID_CLASS_PREFIX), Bill.class).orElse(null);
			
			if (interp != null) {
				interp.setLastPressQuery(LocalDate.now());
				s3.put(interp);
				
//				billService.ddbPersist(bill, interp);
			}
		}
		
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
