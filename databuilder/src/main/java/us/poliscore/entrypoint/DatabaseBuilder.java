package us.poliscore.entrypoint;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.PoliscoreUtil;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.entrypoint.batch.BatchBillRequestGenerator;
import us.poliscore.entrypoint.batch.BatchLegislatorRequestGenerator;
import us.poliscore.entrypoint.batch.BatchOpenAIResponseImporter;
import us.poliscore.entrypoint.batch.PressBillInterpretationRequestGenerator;
import us.poliscore.model.BuildReport;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.session.SessionInterpretation;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorInterpretationService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.PartyInterpretationService;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.PostgresSyncService;

/**
 * Run this to keep a deployed server up-to-date.
 */
@QuarkusMain(name="DatabaseBuilder")
public class DatabaseBuilder implements QuarkusApplication
{
	public static boolean INTERPRET_PRESS_BILLS = false;
	
	public static boolean INTERPRET_NEW_BILLS = false;
	
	public static boolean REINTERPRET_LEGISLATORS = false;
	
	public static boolean REINTERPRET_PARTIES = false;
	
	// Enables the agent to use web searches, but disables batch processing (which doesn't currently support web searches)
	public static boolean AGENTIC_WEB_SEARCH = true;
	
	@Inject
	private BatchBillRequestGenerator billRequestGenerator;
	
	@Inject
	private BatchLegislatorRequestGenerator legislatorRequestGenerator;
	
	@Inject
	private PartyInterpretationService partyInterpreter;
	
	@Inject
	private BatchOpenAIResponseImporter responseImporter;
	
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	private GovernmentDataService data;
	
	@Inject
	protected OpenAIService openAi;
	
	@Inject
	protected PressBillInterpretationRequestGenerator pressBillInterpGenerator;
	
	@Inject
	private LegislatorInterpretationService legInterp;

	@Inject
	private PostgresSyncService postgresSync;
	
	protected BuildReport report = new BuildReport();
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	public BuildReport process() throws IOException
	{
		data.importAllDatasets();
		
		val buildDatasets = data.getBuildDatasets();
		
		initialDataSetup(buildDatasets);
		
		if (!AGENTIC_WEB_SEARCH)
			interpretBillPressArticles(buildDatasets);
		
		interpretBills(buildDatasets);
		pressBillInterpGenerator.recordLastPressQueries(); // We want to record that our press query is complete, but only after the bill has been updated and re-interpreted (otherwise we would need to query again if it fails halfway through)
		
		interpretLegislators(buildDatasets);
		interpretPartyStats(buildDatasets);
		
		syncPostgres(buildDatasets);
		
		System.out.println(report.toString());
		FileUtils.write(new File(Environment.getDeployedPath(), "../buildreport.txt"), report.toString(), "UTF-8");
		
		return report;
	}
	
	protected void initialDataSetup(List<PoliscoreDatasetIF> buildDatasets) {
		for (val dataset : buildDatasets) {
			if (INTERPRET_NEW_BILLS) {
				data.syncS3LegislatorImages(dataset); // TODO : Doesn't really belong in this if switch but it works for my current usecases
				data.syncS3BillText(dataset);
			}
			
			dataset.optimizeExists(s3, BillInterpretation.class);
			dataset.optimizeExists(s3, LegislatorInterpretation.class);
		}
	}
	
	@SneakyThrows
	private void interpretBillPressArticles(List<PoliscoreDatasetIF> buildDatasets) {
		if (INTERPRET_PRESS_BILLS) {
			List<InterpretationRequest> requests = pressBillInterpGenerator.process(buildDatasets);
			
			if (requests.size() > 0) {
				List<File> responses = openAi.processBatch(report, requests);
				
				for (File f : responses) {
					responseImporter.process(report, f);
				}
			}
		}
	}
	
	private void interpretBills(List<PoliscoreDatasetIF> buildDatasets) { interpretBills(buildDatasets, false); }
	@SneakyThrows private void interpretBills(List<PoliscoreDatasetIF> buildDatasets, boolean isRecursive) {
		if (report.hasFatal()) return;
		
		if (INTERPRET_NEW_BILLS) {
			List<InterpretationRequest> requests = billRequestGenerator.process(buildDatasets, report, AGENTIC_WEB_SEARCH, isRecursive);
			
			if (requests.size() > 0) {
				List<File> responses;
				
				if (AGENTIC_WEB_SEARCH)
					responses = openAi.processBatchImmediately(report, requests);
				else
					responses = openAi.processBatch(report, requests);
				
				if (report.hasFatal()) return;
				
				for (File f : responses) {
					responseImporter.process(report, f);
				}
				
				if (!isRecursive)
					interpretBills(buildDatasets, true);
			}
		}
	}
	
	@SneakyThrows
	private void interpretLegislators(List<PoliscoreDatasetIF> buildDatasets) {
		
		// The interpreter will utilize data generated in this process (i.e. aggregate stats)
		legInterp.recalculateAllLegislators();
		
		if (!report.hasFatal() && REINTERPRET_LEGISLATORS) {
			List<InterpretationRequest> requests = legislatorRequestGenerator.process(buildDatasets, report);
		
			if (requests.size() > 0) {
				List<File> responses;
				
				if (AGENTIC_WEB_SEARCH)
					responses = openAi.processBatchImmediately(report, requests);
				else
					responses = openAi.processBatch(report, requests);
				
				if (!report.hasFatal()) {
					for (File f : responses) {
						responseImporter.process(report, f);
					}
				}
			}
		}
		
	}
	
	@SneakyThrows
	private void interpretPartyStats(List<PoliscoreDatasetIF> buildDatasets) {
		if (!report.hasFatal() && REINTERPRET_PARTIES) {
			List<InterpretationRequest> requests = partyInterpreter.interpret(buildDatasets);
			
			if (requests.size() > 0) {
				List<File> responses;
				
				if (AGENTIC_WEB_SEARCH)
					responses = openAi.processBatchImmediately(report, requests);
				else
					responses = openAi.processBatch(report, requests);
				
				if (!report.hasFatal()) {
					for (File f : responses) {
						responseImporter.process(report, f);
					}
				}
			}
		}
		else {
			partyInterpreter.recalculateDatasets(buildDatasets);
		}
	}

	protected void syncPostgres(List<PoliscoreDatasetIF> buildDatasets)
	{
		if (!postgresSync.isEnabled()) {
			return;
		}

		for (val dataset : buildDatasets) {
			postgresSync.syncPostgresWithS3(dataset);
		}
	}
	
	@Override
    public int run(String... args) throws Exception {
		try {
	        process();
	        
	        Quarkus.waitForExit();
	        return 0;
		} catch (Throwable t) {
			t.printStackTrace();
			System.exit(1);
		}
		
		return 1;
    }
	
	public static void main(String[] args) {
		Quarkus.run(DatabaseBuilder.class, args);
		Quarkus.asyncExit(0);
	}
}
