package us.poliscore.entrypoint.batch;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreUtil;
import us.poliscore.WebappDatabase;
import us.poliscore.ai.BatchOpenAIRequest.CustomOriginData;
import us.poliscore.ai.BatchOpenAIResponse;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.dataset.LegiscanDatasetProvider;
import us.poliscore.entrypoint.DatabaseBuilderConfig;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.model.AIAggregateInterpretationMetadata;
import us.poliscore.model.AISliceInterpretationMetadata;
import us.poliscore.model.BuildReport;
import us.poliscore.model.DoubleIssueStats;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.Party;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillInterpretationParser;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.legislator.LegislatorInterpretationParser;
import us.poliscore.model.party.PartyInterpretationParser;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.model.press.PressInterpretationParser;
import us.poliscore.model.session.SessionInterpretation;
import us.poliscore.model.session.SessionInterpretation.PartyInterpretation;
import us.poliscore.service.BillService;
import us.poliscore.service.BillSlicerService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorInterpretationService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.PartyInterpretationService;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.ObjectStorageServiceIF;

/**
 * This bulk importer is designed to import a response from the open ai api.
 */
@QuarkusMain(name="BatchOpenAIResponseImporter")
public class BatchOpenAIResponseImporter implements QuarkusApplication
{
//	public static final String INPUT = new File(System.getProperty("user.home") + "/appdata/poliscore/build/unprocessed.jsonl").getAbsolutePath();
	
//	Canceled half-way through a batch (bills)
	public static final String INPUT = PoliscoreUtil.cacheFile("build/openapi-bills.out.jsonl").getAbsolutePath();
	
	// TODO : If we need to reimport
//	public static final String INPUT = new File(System.getProperty("user.home") + "/appdata/poliscore/build/openapi-legislators-feball.out.jsonl").getAbsolutePath();
	
	public Logger logger = LoggerFactory.getLogger(BatchOpenAIResponseImporter.class);
	
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	private BillService billService;
	
	@Inject
	private LegislatorService legService;
	
	@Inject
	private LegislatorInterpretationService legInterp;
	
	@Inject
	private GovernmentDataService data;
	
	@Inject
	private BillSlicerService billSlicer;
	
	@Inject
	private PartyInterpretationService partyService;
	
	@Inject
	protected PressBillInterpretationRequestGenerator pressBillInterpGenerator;

	@Inject
	private OpenAIService openAiService;

	@Inject
	private DatabaseBuilderConfig runtimeConfig;

	@Inject
	@WebappDatabase
	Instance<ObjectStorageServiceIF> webappStorage;

	@Inject
	CachedLegiscanService legiscan;
	
	private Set<String> importedBills = new HashSet<String>();
	
	private List<Bill> interpretedBillsWithErrors = new ArrayList<Bill>();
	
	private Map<String,SessionInterpretation> sessionInterpMap = new HashMap<String, SessionInterpretation>();
	
	private boolean hasRecalcedLegislators = false;
	
	@SneakyThrows
	public synchronized void process(BuildReport report, File input)
	{
		beginImport();
		
		Log.info("Importing " + input.getAbsolutePath());
		
		@Cleanup BufferedReader reader = new BufferedReader(new FileReader(input));
		String line = reader.readLine();

		while (line != null) {
			processLine(report, line);
			line = reader.readLine();
		}
		
		finishImport(report);
		
		Log.info("Successfully imported " + input.getAbsolutePath());
	}
	
	public synchronized void beginImport() {
		importedBills.clear();
		interpretedBillsWithErrors.clear();
		sessionInterpMap.clear();
		erroredLines.clear();
		hasRecalcedLegislators = false;
	}
	
	@SneakyThrows
	public synchronized boolean processLine(BuildReport report, String line) {
		try {
			val resp = PoliscoreUtil.getObjectMapper().readValue(line, BatchOpenAIResponse.class);
			processResponse(report, resp);
			return true;
		} catch (Throwable t) {
			t.printStackTrace();
			recordErroredLine(report, line);
			return false;
		}
	}
	
	@SneakyThrows
	public synchronized void finishImport(BuildReport report) {
		for (var sessionInterp : sessionInterpMap.values()) {
			val dataset = data.getDataset(sessionInterp.getId());
			if (sessionInterp.isComplete(dataset.hasIndependentPartyMembers())) {
				dataset.put(sessionInterp);
				s3.put(sessionInterp);
			}
		}
		
		if (interpretedBillsWithErrors.size() > 0) {
			// no-op, list already accumulated for report assignment below
		}
		
		if (!erroredLines.isEmpty()) {
			File f = PoliscoreUtil.cacheFile("build/unprocessed.jsonl");
			FileUtils.write(f, String.join("\n", erroredLines), "UTF-8");
			
			String msg = "Encountered errors on " + erroredLines.size() + " lines. Printed them to " + f.getAbsolutePath();
			logger.warn(msg);
			if (report == null)
				throw new RuntimeException(msg);
			else
				report.interpretedBillsWithErrors = interpretedBillsWithErrors;
		}
		erroredLines.clear();
	}

	private final List<String> erroredLines = new ArrayList<String>();

	private void recordErroredLine(BuildReport report, String line) {
		erroredLines.add(line);
		if (report != null) {
			report.interpretedBillsWithErrors = interpretedBillsWithErrors;
		}
	}

	private void processResponse(BuildReport report, BatchOpenAIResponse resp) {
		if (resp.getError() != null || resp.getResponse().getStatus_code() >= 400) {
			String err = "[" + resp.getResponse().getStatus_code() + "] " + resp.getError();
			throw new RuntimeException(err);
		}
		
		if (resp.getCustomData().getOid().startsWith(BillInterpretation.ID_CLASS_PREFIX)) {
			importBill(resp);
		} else if (resp.getCustomData().getOid().startsWith(LegislatorInterpretation.ID_CLASS_PREFIX)) {
			importLegislator(resp);
		} else if (resp.getCustomData().getOid().startsWith(PressInterpretation.ID_CLASS_PREFIX)) {
			importPressInterp(resp);
		} else if (resp.getCustomData().getOid().startsWith(SessionInterpretation.ID_CLASS_PREFIX)) {
			importParty(resp);
		} else {
			throw new UnsupportedOperationException("Unexpected object type " + resp.getCustom_id());
		}
	}
	
	private void importLegislator(final BatchOpenAIResponse resp) {
		if (!hasRecalcedLegislators) {
			legInterp.recalculateAllLegislators();
			hasRecalcedLegislators = true;
		}
		
		val dataset = data.getDataset(resp.getCustomData().getOid());
		val leg = dataset.get(resp.getCustomData().getOid().replace(LegislatorInterpretation.ID_CLASS_PREFIX, Legislator.ID_CLASS_PREFIX), Legislator.class).orElseThrow();
		
		LegislatorInterpretation interp = leg.getInterpretation();
		
		if (interp == null)
			throw new UnsupportedOperationException(leg.getId() + " interpretation was null!");
		
		interp.setMetadata(openAiService.metadata(responseModel(resp)));
		interp.setHash(legInterp.calculateInterpHashCode(leg));
		interp.setLastUpdate(LocalDateTime.now());
		
		val interpText = resp.getResponse().getBody().getChoices().get(0).getMessage().getContent();
		val parser = new LegislatorInterpretationParser(interp);
		parser.parse(interpText);
		
		legService.persistMediaReferences(parser.getMediaReferences());
		s3.put(interp);
		legService.applyInterpretation(leg, interp);
	}
	
	private void importParty(final BatchOpenAIResponse resp) {
		val dataset = data.getDataset(resp.getCustomData().getOid());
		
		// SIT/us/co/2243/DEMOCRAT
		String sessionKey = resp.getCustomData().getOid().split("/")[1] + "/" + resp.getCustomData().getOid().split("/")[2] + "/" + resp.getCustomData().getOid().split("/")[3];
		String partyName = resp.getCustomData().getOid().split("/")[4];
		
		SessionInterpretation sessionInterp;
		if (!sessionInterpMap.containsKey(sessionKey)) {
			if (!hasRecalcedLegislators) {
				legInterp.recalculateAllLegislators();
				hasRecalcedLegislators = true;
			}
			
			sessionInterp = partyService.recalculateStats(dataset);
		} else {
			sessionInterp = sessionInterpMap.get(sessionKey);
		}
		
		PartyInterpretation partyInterp;
		if (partyName.equals(Party.DEMOCRAT.name())) {
			partyInterp = sessionInterp.getDemocrat();
		} else if (partyName.equals(Party.REPUBLICAN.name())) {
			partyInterp = sessionInterp.getRepublican();
		} else if (partyName.equals(Party.INDEPENDENT.name())) {
			partyInterp = sessionInterp.getIndependent();
		} else {
			throw new UnsupportedOperationException();
		}
		
		val interpText = resp.getResponse().getBody().getChoices().get(0).getMessage().getContent();
		new PartyInterpretationParser(partyInterp).parse(interpText);
		
		sessionInterp.setMetadata(openAiService.metadata(responseModel(resp)));
		
		sessionInterpMap.put(sessionKey, sessionInterp);
	}

	@SneakyThrows
	private void importBill(final BatchOpenAIResponse resp) {
		val parsedId = BillInterpretation.parseId(resp.getCustomData().getOid());
		String billId = parsedId.billId();
		Integer sliceIndex = parsedId.sliceIndex();
		String modelId = responseModel(resp);
		
		val bill = resolveBillForImport(billId);
		if (bill == null) {
			throw new IllegalStateException("Could not resolve bill for import: " + billId);
		}
		
		try {
			BillInterpretation bi = new BillInterpretation();
			bi.setBill(bill);
			BillText sourceBillText = resolveSourceBillText(bill, parsedId.sourceBillTextVersion());
			
			if (sliceIndex == null)
			{
				bi.setId(BillInterpretation.generateId(bill.getId(), sourceBillText.getVersion(), null));
				
				if (s3.exists(BillInterpretation.generateId(bill.getId(), sourceBillText.getVersion(), 0), BillInterpretation.class)) {
					String sessionKey = billId.substring(StringUtils.ordinalIndexOf(billId, "/", 1)+1, StringUtils.ordinalIndexOf(billId, "/", 4));
					String objectKey = billId.substring(StringUtils.ordinalIndexOf(billId, "/", 4)+1);
					
					List<BillSlice> slices = new ArrayList<BillSlice>();
					for (var sliceInterp : s3.query(BillInterpretation.class, sessionKey, objectKey)) {
						if (sliceInterp.getBillId().equals(bill.getId())
								&& sliceInterp.getMetadata() instanceof AISliceInterpretationMetadata
								&& StringUtils.equalsIgnoreCase(StringUtils.defaultString(sourceBillText.getVersion()), StringUtils.defaultString(sliceInterp.getSourceBillTextVersion()))) {
							var sliceMetadata = (AISliceInterpretationMetadata) sliceInterp.getMetadata();
							slices.add(new BillSlice(bill, null, sliceInterp.getSliceIndex(), sliceInterp.getGenBillTitle(), sliceInterp.getShortExplain(), sliceMetadata.getStart(), sliceMetadata.getEnd()));
						}
					}
					slices.sort(Comparator.comparing(BillSlice::getSliceIndex));
					bi.setMetadata(openAiService.metadata(modelId, slices));
				} else
					bi.setMetadata(openAiService.metadata(modelId));
			}
			else
			{
				bill.setText(sourceBillText);
				
					List<BillSlice> slices = billSlicer.slice(bill, sourceBillText, BatchBillRequestGenerator.BillGenerationCriteria.defaultCriteria().getBillProcessModel());
				
				bi.setMetadata(openAiService.metadata(modelId, slices.get(sliceIndex)));
				bi.setId(BillInterpretation.generateId(billId, sourceBillText.getVersion(), sliceIndex));
			}

				bi.setSourceBillTextVersion(sourceBillText.getVersion());
			
			var msg = resp.getResponse().getBody().getChoices().get(0).getMessage();
			var interpText = msg.getContent();
			
			if (interpText.contains("NO_INTERPRETATION")) {
				Log.info("Bill interpretation " + bi.getId() + " <" + bi.getOrigin().getUrl() + "> was determined by AI as " + interpText);
				return;
			}
			
			if (runtimeConfig.isAgenticWebSearch())
				billService.wipeAllPressInterps(bill);
			
			new BillInterpretationParser(bill, bi, s3).parse(interpText, null);
			
			if (!bi.getIssueStats().hasStat(TrackedIssue.OverallBenefitToSociety)) {
				if (sliceIndex != null) {throw new RuntimeException("Did not find OverallBenefitToSociety stat on interpretation");  }
				
				List<BillSlice> slices = ((AIAggregateInterpretationMetadata)bi.getMetadata()).getSlices();
				
				if (slices.size() <= 1) { throw new RuntimeException("Expected multiple slices on [" + billId + "] since OpenAI did not include benefit to society issue stat"); }
				
				DoubleIssueStats billStats = new DoubleIssueStats();
				List<BillInterpretation> sliceInterps = new ArrayList<BillInterpretation>();
				
				for (int i = 0; i < slices.size(); ++i) {
					val sliceInterp = s3.get(BillInterpretation.generateId(billId, sourceBillText.getVersion(), i), BillInterpretation.class).orElseThrow();
					
					billStats = billStats.sum(sliceInterp.getIssueStats().toDoubleIssueStats());
					sliceInterps.add(sliceInterp);
				}
				
				bi.setIssueStats(billStats.divideByTotalSummed().toIssueStats());
			}
			
			if (StringUtils.isBlank(bi.getLongExplain()) || (sliceIndex == null && (StringUtils.isBlank(bi.getShortExplain()) || bi.getIssueStats() == null || !bi.getIssueStats().hasStat(TrackedIssue.OverallBenefitToSociety)))) {
				throw new RuntimeException("Interpretation missing proper stats or explain." + billId);
			}
			
			if (runtimeConfig.isAgenticWebSearch() || pressBillInterpGenerator.getQueriedBills().contains(bill)) {
				bi.setLastPressQuery(LocalDate.now());
			}
			
			LocalDateTime generatedAt = LocalDateTime.now();
			bi.recordGeneration(generatedAt, s3.get(bi.getId(), BillInterpretation.class).orElse(null));
			
			if (bi.getOrigin().equals(InterpretationOrigin.POLISCORE) && sliceIndex == null) {
				billService.applyInterpretation(bill, bi);
				importedBills.add(billId);
			}
			
			// PopulatePressInterps must be called before we do this (which happens in billService.applyInterpretation)
			s3.put(bi);
		} catch (Throwable t) {
			interpretedBillsWithErrors.add(bill);
			throw t;
		}
	}

	private String responseModel(BatchOpenAIResponse resp) {
		String model = resp == null || resp.getResponse() == null || resp.getResponse().getBody() == null
				? null
				: resp.getResponse().getBody().getModel();
		return StringUtils.defaultIfBlank(model, OpenAIModel.DEFAULT_MODEL.getId());
	}

	private BillText resolveSourceBillText(Bill bill, String requestedVersion) {
		if (StringUtils.isBlank(requestedVersion)) {
			return billService.getBillText(bill).orElseThrow();
		}

		return billService.getBillTexts(bill).stream()
				.filter(text -> StringUtils.equalsIgnoreCase(requestedVersion, text.getVersion()))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Bill text version not found for " + bill.getId() + ": " + requestedVersion));
	}

	private Bill resolveBillForImport(String billId) {
		try {
			val dataset = data.getDataset(billId);
			val bill = dataset.get(billId, Bill.class).orElse(null);
			if (bill != null) {
				return bill;
			}
		} catch (NoSuchElementException ignored) {
			// Deployed webapp environments do not always have imported datasets.
		}

		if (webappStorage.isResolvable()) {
			val bill = webappStorage.get().get(billId, Bill.class).orElse(null);
			if (bill == null) {
				return null;
			}

			val legiscanBill = legiscan.getBill(bill.getLegiscanId());
			LegiscanDatasetProvider.populate(bill, legiscanBill, null);
			return bill;
		}

		return null;
	}
	
	@SneakyThrows
	private void importPressInterp(final BatchOpenAIResponse resp) {
		String billId = resp.getCustomData().getOid().replace(PressInterpretation.ID_CLASS_PREFIX, Bill.ID_CLASS_PREFIX);
		
		val dashSplit = billId.split("-");
		if (dashSplit.length == 2) {
			billId = dashSplit[0];
		}
		
		PressInterpretation bi = new PressInterpretation();
		bi.setBillId(billId);
		bi.setOrigin(((CustomOriginData) resp.getCustomData()).getOrigin());
		bi.setMetadata(pressBillInterpGenerator.metadata());
		bi.setId(PressInterpretation.generateId(billId, bi.getOrigin()));
		
		var interpText = resp.getResponse().getBody().getChoices().get(0).getMessage().getContent();
		
		if (interpText.contains("NO_INTERPRETATION")) {
			Log.info("Press interpretation " + bi.getId() + " <" + bi.getOrigin().getUrl() + "> was determined by AI as " + interpText);
			bi.setShortExplain(interpText);
			bi.setNoInterp(true);
			s3.put(bi);
			return;
		}
		
		bi.setNoInterp(false);
		
		new PressInterpretationParser(bi).parse(interpText);
		
		if (StringUtils.isBlank(bi.getLongExplain()) || StringUtils.isBlank(bi.getShortExplain()) || bi.getSentiment() > 100 || bi.getSentiment() < -100) {
			Log.error(interpText);
			throw new RuntimeException("Press interpretation missing required field. " + billId);
		}
		
		s3.put(bi);
	}
	
	@Override
    public int run(String... args) throws Exception {
        process(null, new File(INPUT));
        
        Quarkus.waitForExit();
        return 0;
    }
	
	public static void main(String[] args) {
		Quarkus.run(BatchOpenAIResponseImporter.class, args);
		Quarkus.asyncExit(0);
	}
}
