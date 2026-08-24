package us.poliscore.entrypoint;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;

import jakarta.enterprise.context.ApplicationScoped;
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
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.legislator.LegislatorMediaReference;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.model.session.SessionInterpretation;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorInterpretationService;
import us.poliscore.service.MemoryObjectService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.PartyInterpretationService;
import us.poliscore.service.PoliscoreConfigService;
import us.poliscore.service.SessionInfoService;
import us.poliscore.service.storage.LocalCachedS3Service;

/**
 * Run this to keep a deployed server up-to-date.
 */
@ApplicationScoped
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
	private PoliscoreConfigService config;

	@Inject
	private MemoryObjectService memory;
	
	@Inject
	protected OpenAIService openAi;
	
	@Inject
	protected PressBillInterpretationRequestGenerator pressBillInterpGenerator;
	
	@Inject
	private LegislatorInterpretationService legInterp;

	@Inject
	private DatabaseBuilderConfig runtimeConfig;
	
	protected BuildReport report = new BuildReport();
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	public BuildReport process() throws IOException
	{
		report = new BuildReport();
		pressBillInterpGenerator.beginBuildRun();

		for (var deployment : config.getSupportedDeployments()) {
			if (!Boolean.TRUE.equals(deployment.getBuild())) continue;

			data.resetImports();
			try {
				val dataset = data.importDataset(deployment, report);
				data.importPreviousDataset(dataset, report);
				processDataset(dataset);
			} finally {
				cleanupImportedDatasets(List.copyOf(data.getAllImportedDatasets()));
				data.resetImports();
			}

			if (report.hasBlockingFatal()) break;
		}

		System.out.println(report.toString());
		FileUtils.write(new File(Environment.getDeployedPath(), "../buildreport.txt"), report.toString(), "UTF-8");
		
		return report;
	}

	protected void processDataset(PoliscoreDatasetIF dataset) {
		initialDataSetup(dataset);

		if (!runtimeConfig.isAgenticWebSearch())
			interpretBillPressArticles(dataset);

		interpretBills(dataset);
		pressBillInterpGenerator.recordLastPressQueries(); // Record only after this dataset's bill updates have been imported.

		interpretLegislators(dataset);
		interpretPartyStats(dataset);
	}
	
	protected void initialDataSetup(PoliscoreDatasetIF dataset) {
		if (runtimeConfig.isInterpretNewBills()) {
			data.syncS3LegislatorImages(dataset); // TODO : Doesn't really belong in this if switch but it works for my current usecases
			data.syncS3BillText(dataset);
		}

		dataset.optimizeExists(s3, LegislatorInterpretation.class);

		for (val importedDataset : data.getAllImportedDatasets()) {
			importedDataset.optimizeExists(s3, BillInterpretation.class);
		}
	}
	
	@SneakyThrows
	private void interpretBillPressArticles(PoliscoreDatasetIF dataset) {
		if (runtimeConfig.isInterpretPressBills()) {
			List<InterpretationRequest> requests = pressBillInterpGenerator.processWithinBuild(List.of(dataset));
			markFlex(requests, runtimeConfig.isFlexRequests());
			
			if (requests.size() > 0) {
				List<File> responses = openAi.processBatch(report, requests);
				
				for (File f : responses) {
					responseImporter.process(report, f);
				}
			}
		}
	}
	
	private void interpretBills(PoliscoreDatasetIF dataset) { interpretBills(dataset, false); }
	@SneakyThrows private void interpretBills(PoliscoreDatasetIF dataset, boolean isRecursive) {
		if (report.hasBlockingFatal()) return;
		
		if (runtimeConfig.isInterpretNewBills()) {
			List<InterpretationRequest> requests = billRequestGenerator.process(List.of(dataset), report, runtimeConfig.isAgenticWebSearch(), isRecursive);
			markFlex(requests, runtimeConfig.isFlexRequests());
			
			if (requests.size() > 0) {
				List<File> responses;
				
				if (runtimeConfig.isAgenticWebSearch())
					responses = openAi.processBatchImmediately(report, requests);
				else
					responses = openAi.processBatch(report, requests);
				
				if (report.hasBlockingFatal()) return;
				
				for (File f : responses) {
					responseImporter.process(report, f);
				}
				
				if (!isRecursive)
					interpretBills(dataset, true);
			}
		}
	}
	
	@SneakyThrows
	private void interpretLegislators(PoliscoreDatasetIF dataset) {
		
		// The interpreter will utilize data generated in this process (i.e. aggregate stats)
		legInterp.recalculateLegislators(dataset);
		
		if (!report.hasBlockingFatal() && runtimeConfig.isReinterpretLegislators()) {
			List<InterpretationRequest> requests = legislatorRequestGenerator.process(List.of(dataset), report);
			markFlex(requests, runtimeConfig.isFlexRequests());
		
			if (requests.size() > 0) {
				List<File> responses;
				
				if (runtimeConfig.isAgenticWebSearch())
					responses = openAi.processBatchImmediately(report, requests);
				else
					responses = openAi.processBatch(report, requests);
				
				if (!report.hasBlockingFatal()) {
					for (File f : responses) {
						responseImporter.process(report, f);
					}
				}
			}
		}
		
	}
	
	@SneakyThrows
	private void interpretPartyStats(PoliscoreDatasetIF dataset) {
		if (!report.hasBlockingFatal() && runtimeConfig.isReinterpretParties()) {
			List<InterpretationRequest> requests = partyInterpreter.interpret(List.of(dataset));
			markFlex(requests, runtimeConfig.isFlexRequests());
			
			if (requests.size() > 0) {
				List<File> responses;
				
				if (runtimeConfig.isAgenticWebSearch())
					responses = openAi.processBatchImmediately(report, requests);
				else
					responses = openAi.processBatch(report, requests);
				
				if (!report.hasBlockingFatal()) {
					for (File f : responses) {
						responseImporter.process(report, f);
					}
				}
			}
		}
		else {
			partyInterpreter.recalculateDatasets(List.of(dataset));
		}
	}

	protected void cleanupImportedDatasets(List<PoliscoreDatasetIF> importedDatasets) {
		for (var dataset : importedDatasets) {
			dataset.clearExistsOptimize(s3, BillInterpretation.class);
			dataset.clearExistsOptimize(s3, BillText.class);
			dataset.clearExistsOptimize(s3, LegislatorInterpretation.class);
			dataset.clearExistsOptimize(s3, LegislatorMediaReference.class);
			dataset.clearExistsOptimize(s3, PressInterpretation.class);
			dataset.clearExistsOptimize(s3, SessionInterpretation.class);
		}
		memory.clearSessions(SessionInfoService.sessionsForDatasets(importedDatasets));
	}

	private void markFlex(List<InterpretationRequest> requests, boolean flex) {
		requests.forEach(request -> request.setFlex(flex));
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
