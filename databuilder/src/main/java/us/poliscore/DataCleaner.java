package us.poliscore;

import java.io.IOException;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.service.BillInterpretationService;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorInterpretationService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name="DataCleaner")
public class DataCleaner implements QuarkusApplication {
	
	@Inject private LegislatorInterpretationService legService;
	
	@Inject private BillService billService;
	
	@Inject
	private BillInterpretationService billInterpreter;
	
	@Inject private GovernmentDataService data;
	
	@Inject
	private LocalCachedS3Service s3;
	
	protected void process() throws IOException
	{
		val dataset = data.importDataset(LegislativeNamespace.US_CONGRESS, 2026);
//		val dataset = data.importDataset(LegislativeNamespace.US_COLORADO, 2026);
		
//		val interp = s3.get(LegislatorInterpretation.generateId(dataset.getNamespace(), "119", "C001116"), LegislatorInterpretation.class).get();
//		
//		interp.validate();
//		
//		System.out.println(LegislatorInterpretationParser.stripMultiLines(interp.getShortExplain()));
		
//		deleteInvalidBillTexts(dataset);
		
//		validateLegislatorInterps(dataset);
		
//		reparseStructuralAnalysisInterps(dataset);
		
		
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
