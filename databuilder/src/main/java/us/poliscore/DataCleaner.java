package us.poliscore;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillInterpretationParser.StructuralAnalysisParser;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.service.BillInterpretationService;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorInterpretationService;
import us.poliscore.service.storage.DynamoDbPersistenceService;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.S3PersistenceService.QueryCriteria;

@QuarkusMain(name="DataCleaner")
public class DataCleaner implements QuarkusApplication {
	
	@Inject private LegislatorInterpretationService legService;
	
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
//		val dataset = data.importDataset(LegislativeNamespace.US_CONGRESS, 2026);
		val dataset = data.importDataset(LegislativeNamespace.US_COLORADO, 2026);
		
		deleteInvalidBillTexts(dataset);
		
//		validateLegislatorInterps(dataset);
		
//		reparseStructuralAnalysisInterps(dataset);
        
		System.out.println("Program complete.");
	}
	
	public void deleteInvalidBillTexts(PoliscoreDatasetIF dataset) {
		int count = 0;
		
		for (Bill b : dataset.query(Bill.class)) {
			var dbill = ddb.get(b.getId(), Bill.class).orElse(null);
			
			val op = s3.get(BillText.generateId(b.getId()), BillText.class);
			
			if (op.isPresent() && StringUtils.isBlank(op.get().getDocument())) {
				s3.delete(BillText.generateId(b.getId()), BillText.class);
				count++;
			}
		}
		
		System.out.println("Deleted " + count + " corrupt bill texts");
	}
	
	public void reparseStructuralAnalysisInterps(PoliscoreDatasetIF dataset) {
		
		int count = 0;
		for (Bill b : dataset.query(Bill.class)) {
			var dbill = ddb.get(b.getId(), Bill.class).orElse(null);
			
			val interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class);
			if (interp.isEmpty() || dbill == null || dbill.getInterpretation() == null) continue;
			
			if (StringUtils.isNotBlank(dbill.getInterpretation().getStructuralAnalysisRaw()) && (dbill.getInterpretation().getStructuralAnalysisExplain() == null || dbill.getInterpretation().getStructuralAnalysisExplain().isEmpty())) {
				StructuralAnalysisParser.StructuralAnalysisParsed saParsed = StructuralAnalysisParser.parse(interp.get().getStructuralAnalysisRaw());
				interp.get().setStructuralAnalysisPassFail(saParsed.getResults());
		        interp.get().setStructuralAnalysisExplain(saParsed.getAnalyses());
		        
//		        s3.put(interp.get());
		        
		        billService.ddbPersist(dbill, interp.get());
		        
		        count++;
			}
		}
		
		System.out.println("Updated " + count + " bills.");
	}
	
	public void printLegislatorsBills(Legislator leg, PoliscoreDataset dataset) {
		Set<String> ids = new HashSet<String>();
		
		legService.updateInteractionsInterp(leg);
		
		for (val interact : legService.getInteractionsForInterpretation(leg).stream().filter(i ->
				i.getRating() < 0 && s3.get(BillInterpretation.generateId(i.getBillId(), null), BillInterpretation.class).get().getMetadata().getDate().isBefore(LocalDate.of(2025, 8, 3))
			).toList()) {
			ids.add(interact.getBillId());
		}
		
		System.out.println(StringUtils.join(ids.stream().map(id -> "\"" + id + "\"").toList(), ","));
		System.out.println("Count: " + ids.size());
	}
	
	public void lookForNullRating(PoliscoreDataset dataset) {
		val legId = Legislator.generateId(LegislativeNamespace.US_CONGRESS, "119", "P000595");
		val leg = ddb.get(legId, Legislator.class).get();
//		val leg = dataset.get(legId, Legislator.class).get();
		
		int hasRating = 0;
		int notRating = 0;
		
		for (val i : legService.getInteractionsForInterpretation(leg)) {
			if (i.getRating() == null) {
				System.out.println(i.getBillId());
				notRating++;
			} else {
				hasRating++;
			}
		}
		
		System.out.println("Has rating: " + hasRating + " no rating " + notRating);
	}
	
	public void cleanQualityMetric(PoliscoreDataset dataset) {
		for (Bill b : dataset.query(Bill.class).stream().filter(b -> billInterpreter.isInterpreted(b.getId())).collect(Collectors.toList())) {
			val interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).get();
			
			if (interp.getRating() < 0 && interp.getQuality() != null && interp.getQuality() > 0)
				System.out.println(interp.getBillId());
//				s3.delete(interp.getId(), BillInterpretation.class);

		}
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
	
	public void validateLegislatorInterps(PoliscoreDatasetIF dataset) {
		Log.info("validating legislator interpretations.");
		
		int total = 0;
		int invalid = 0;
		
		for (val leg : dataset.query(Legislator.class)) {
			var interpOp = s3.get(LegislatorInterpretation.generateId(dataset.getNamespace(), dataset.getCode(), leg.getCode()), LegislatorInterpretation.class);
			
			if (interpOp.isPresent())
			{
				var interp = interpOp.get();
				
				try {
					interp.validate();
				} catch (IllegalStateException ex) {
					invalid++;
					Log.info("Exception thrown validating interp " + interp.getId(), ex);
				}
			}
			
			total++;
		}
		
		Log.info("Finished validating legislator interps. Found " + invalid + " invalid interps of total " + total);
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

