package us.poliscore.entrypoint;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
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
@Command(name = "DatabaseBuilder", mixinStandardHelpOptions = true, description = "Keeps the deployed server up to date.")
public class DatabaseBuilder implements QuarkusApplication, Callable<Integer>
{
	// Default values for these parameters are set in the DatabaseBuilderRuntimeConfig class
	
	@Option(names = "--interpret-press-bills", negatable = true, description = "Whether to interpret press bills.")
	Boolean interpretPressBills;

	@Option(names = "--interpret-new-bills", negatable = true, description = "Whether to interpret new bills.")
	Boolean interpretNewBills;

	@Option(names = "--reinterpret-legislators", negatable = true, description = "Whether to reinterpret legislators.")
	Boolean reinterpretLegislators;

	@Option(names = "--reinterpret-parties", negatable = true, description = "Whether to reinterpret parties.")
	Boolean reinterpretParties;

	@Option(names = "--flex-requests", negatable = true, description = "Whether DatabaseBuilder requests should use the flex tier.")
	Boolean flexRequests;

	@Option(names = "--agentic-web-search", negatable = true, description = "Whether bill interpretation should use agentic web search.")
	Boolean agenticWebSearch;
	
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

	@Inject
	private DatabaseBuilderRuntimeConfig runtimeConfig;
	
	protected BuildReport report = new BuildReport();
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	public BuildReport process() throws IOException
	{
		data.importAllDatasets();
		
		val buildDatasets = data.getBuildDatasets();
		
		initialDataSetup(buildDatasets);
		
		if (!runtimeConfig.isAgenticWebSearch())
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
			if (runtimeConfig.isInterpretNewBills()) {
				data.syncS3LegislatorImages(dataset); // TODO : Doesn't really belong in this if switch but it works for my current usecases
				data.syncS3BillText(dataset);
			}
			
			dataset.optimizeExists(s3, BillInterpretation.class);
			dataset.optimizeExists(s3, LegislatorInterpretation.class);
		}
	}
	
	@SneakyThrows
	private void interpretBillPressArticles(List<PoliscoreDatasetIF> buildDatasets) {
		if (runtimeConfig.isInterpretPressBills()) {
			List<InterpretationRequest> requests = pressBillInterpGenerator.process(buildDatasets);
			markFlex(requests, runtimeConfig.isFlexRequests());
			
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
		
		if (runtimeConfig.isInterpretNewBills()) {
			List<InterpretationRequest> requests = billRequestGenerator.process(buildDatasets, report, runtimeConfig.isAgenticWebSearch(), isRecursive);
			markFlex(requests, runtimeConfig.isFlexRequests());
			
			if (requests.size() > 0) {
				List<File> responses;
				
				if (runtimeConfig.isAgenticWebSearch())
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
		
		if (!report.hasFatal() && runtimeConfig.isReinterpretLegislators()) {
			List<InterpretationRequest> requests = legislatorRequestGenerator.process(buildDatasets, report);
			markFlex(requests, runtimeConfig.isFlexRequests());
		
			if (requests.size() > 0) {
				List<File> responses;
				
				if (runtimeConfig.isAgenticWebSearch())
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
		if (!report.hasFatal() && runtimeConfig.isReinterpretParties()) {
			List<InterpretationRequest> requests = partyInterpreter.interpret(buildDatasets);
			markFlex(requests, runtimeConfig.isFlexRequests());
			
			if (requests.size() > 0) {
				List<File> responses;
				
				if (runtimeConfig.isAgenticWebSearch())
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

	private void markFlex(List<InterpretationRequest> requests, boolean flex) {
		requests.forEach(request -> request.setFlex(flex));
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
			return new CommandLine(this).execute(args);
		} catch (Throwable t) {
			t.printStackTrace();
			System.exit(1);
		}
		
		return 1;
    }

	@Override
	public Integer call() throws Exception {
		applyCliConfiguration();
		logConfiguration();
		process();
		Quarkus.waitForExit();
		return 0;
	}

	private void applyCliConfiguration() {
		if (interpretPressBills != null) {
			runtimeConfig.setInterpretPressBills(interpretPressBills);
		}
		if (interpretNewBills != null) {
			runtimeConfig.setInterpretNewBills(interpretNewBills);
		}
		if (reinterpretLegislators != null) {
			runtimeConfig.setReinterpretLegislators(reinterpretLegislators);
		}
		if (reinterpretParties != null) {
			runtimeConfig.setReinterpretParties(reinterpretParties);
		}
		if (flexRequests != null) {
			runtimeConfig.setFlexRequests(flexRequests);
		}
		if (agenticWebSearch != null) {
			runtimeConfig.setAgenticWebSearch(agenticWebSearch);
		}
	}

	private void logConfiguration() {
		System.out.println("DatabaseBuilder configuration:");
		System.out.println("  interpret-press-bills=" + runtimeConfig.isInterpretPressBills());
		System.out.println("  interpret-new-bills=" + runtimeConfig.isInterpretNewBills());
		System.out.println("  reinterpret-legislators=" + runtimeConfig.isReinterpretLegislators());
		System.out.println("  reinterpret-parties=" + runtimeConfig.isReinterpretParties());
		System.out.println("  flex-requests=" + runtimeConfig.isFlexRequests());
		System.out.println("  agentic-web-search=" + runtimeConfig.isAgenticWebSearch());
	}
	
	public static void main(String[] args) {
		Quarkus.run(DatabaseBuilder.class, args);
		Quarkus.asyncExit(0);
	}
}
