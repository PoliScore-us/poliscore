package us.poliscore.entrypoint.batch;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.openai.models.ReasoningEffort;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.BuildReport;
import us.poliscore.model.IssueStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorBillInteraction;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorInterpretationService;
import us.poliscore.service.storage.S3PersistenceService;

/**
 * Generates a bulk request to open ai for all legislators 
 */
@QuarkusMain(name="BatchLegislatorRequestGenerator")
public class BatchLegislatorRequestGenerator implements QuarkusApplication
{
	public static final List<String> specificFetch = null;
//	public static final List<String> specificFetch = Arrays.asList(
//			Legislator.generateId(LegislativeNamespace.US_CONGRESS, "119", "L000583"),
//			Legislator.generateId(LegislativeNamespace.US_CONGRESS, "119", "H001100"),
//			Legislator.generateId(LegislativeNamespace.US_CONGRESS, "119", "B000825"),
//			Legislator.generateId(LegislativeNamespace.US_CONGRESS, "119", "C001137"),
//			Legislator.generateId(LegislativeNamespace.US_CONGRESS, "119", "E000300")
//		);
	
	public static final LocalDateTime OLDER_THAN = null;
//	public static final LocalDateTime OLDER_THAN = specificFetch == null ? Period.ofMonths(1) : null;
//	public static final LocalDateTime OLDER_THAN = LocalDateTime.now().minus(Period.ofMonths(20));
//	public static final LocalDateTime OLDER_THAN = LocalDate.of(2025, 8, 14).atStartOfDay();
	
	public static final int MAX_REQUESTS = specificFetch != null ? -1 : 1000;
	
//	public static final boolean CHECK_S3_EXISTS = false;
	public static final boolean CHECK_S3_EXISTS = specificFetch == null && OLDER_THAN == null;
	
	public static final OpenAIModel interpModel = OpenAIModel.DEFAULT_SUBSCRIBER_MODEL;
	
	@Inject
	private S3PersistenceService s3;
	
	@Inject
	private GovernmentDataService data;
	
	@Inject
	private LegislatorInterpretationService legInterp;
	
	private long skipped = 0;
	
	private List<InterpretationRequest> requests = new ArrayList<InterpretationRequest>();
	
	protected BuildReport report;
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	public List<InterpretationRequest> process(List<PoliscoreDatasetIF> buildDatasets, BuildReport report) throws IOException
	{
		Log.info("Generating batch request to interpret legislators");
		
		this.report = report;
		
		data.importAllDatasets();
		
		int block = 1;
		
		for (PoliscoreDatasetIF dataset : data.getBuildDatasets())
		{
			dataset.optimizeExists(s3, LegislatorInterpretation.class);
			processDataset(dataset, true, block);
		}
		
		Log.info("Batch legislator request generator complete. Generated " + requests.size() + " requests. Skipped " + skipped + " legislators.");
		
		return requests;
	}
	
	private void processDataset(PoliscoreDatasetIF dataset, boolean enableWebSearch, int block) throws IOException {
		Log.info("Processing legislators for dataset " + dataset.getDescription());
		
		var list = dataset.query(Legislator.class).stream()
				.filter(l -> specificFetch == null || specificFetch.contains(l.getId()))
				.filter(l -> l.getInteractions().size() > 0)
				.filter(l -> !CHECK_S3_EXISTS || !s3.exists(LegislatorInterpretation.generateId(dataset.getNamespace(), dataset.getRegularSession().getCode(), l.getCode()), LegislatorInterpretation.class))
				.filter(l -> interpIsOlderThan(l, dataset))
//				.filter(l -> interpHasGrade("D", l, dataset) || interpHasGrade("C", l, dataset))
//				.filter(l -> l.getNamespace().equals(LegislativeNamespace.US_COLORADO)
//						&& s3.exists(LegislatorInterpretation.generateId(dataset.getNamespace(), dataset.getCode(), l.getCode()), LegislatorInterpretation.class)
//						&& !s3.get(LegislatorInterpretation.generateId(dataset.getNamespace(), dataset.getCode(), l.getCode()), LegislatorInterpretation.class).get().getMetadata().getModel().toLowerCase().equals("gpt-5"))
				.filter(l -> hasEnoughInteractions(l, dataset))
				.sorted(Comparator.comparing(Legislator::getDate).reversed())
				.toList();
		
		var total = "";
		
		if (MAX_REQUESTS != -1) {
			total = list.size() > 0 ? " " + (list.size() - Math.min(list.size(), MAX_REQUESTS)) + " left to process after this batch" : "";
			list = list.subList(0, Math.min(list.size(), MAX_REQUESTS));
		}
		
		Log.info("Generating requests for " + list.size() + " legislators." + total);
		
		for (Legislator l : list) {
			interpret(dataset, l);
		}
	}
	
	private boolean interpHasGrade(String grade, Legislator leg, PoliscoreDatasetIF dataset) {
		val interpOp = s3.get(LegislatorInterpretation.generateId(dataset.getNamespace(), dataset.getRegularSession().getCode(), leg.getCode()), LegislatorInterpretation.class);
		if (interpOp.isEmpty()) return true;
		if (interpOp.get().getIssueStats() == null) return true;
		
		return interpOp.get().getIssueStats().getLetterGrade(dataset.getConfig().getMultiplier()).trim().equalsIgnoreCase(grade.trim());
	}
	
	private boolean interpIsOlderThan(Legislator leg, PoliscoreDatasetIF dataset) {
		if (OLDER_THAN == null) return true;
		
		val interpOp = s3.get(LegislatorInterpretation.generateId(dataset.getNamespace(), dataset.getRegularSession().getCode(), leg.getCode()), LegislatorInterpretation.class);
		if (interpOp.isEmpty()) return true;
		if (interpOp.get().getLastUpdate() == null) return true;
		
		return interpOp.get().getLastUpdate().isBefore(OLDER_THAN);
	}
	
	private boolean hasEnoughInteractions(Legislator leg, PoliscoreDatasetIF dataset) {
		legInterp.backfillInteractionsFromPreviousSession(leg, data.getPreviousDataset(dataset));
		
		if (!legInterp.meetsInterpretationPrereqs(leg))
		{
			Log.info("Skipping " + leg.getId() + " (" + leg.getName().getOfficial_full() + ") because he did not have at least 30 interactions.");
			skipped++;
			return false;
		}
		
		return true;
	}
	
	protected void interpret(PoliscoreDatasetIF dataset, Legislator leg)
	{
//		val interpOp = s3.get(LegislatorInterpretation.generateId(dataset.getNamespace(), dataset.getCode(), leg.getCode()), LegislatorInterpretation.class);
//		if (interpOp.isPresent())
//		{
//			val interp = interpOp.get();
//			if (interp.getMetadata().getModel().equals(OpenAIModel.GPT5.getId())) {
//				Log.info("Skipping legislator " + leg.getId() + " because his interpretation was generated by GPT-5.");
//				return;
//			} else {
//				Log.info(leg.getId() + " : " + interp.getMetadata().getModel());
//			}
//		}
		
		if (leg.getInterpretation() == null) {
			Log.info("Skipping " + leg.getId() + " because they did not have an interpretation.");
			return;
		}
		
		var stats = leg.getInterpretation().getIssueStats();
		if (stats == null) {
			Log.info("Skipping " + leg.getId() + " because they did not have any issue stats.");
			return;
		}
		String grade = stats.getLetterGrade(dataset.getConfig().getMultiplier());
		
		List<String> billMsgs = new ArrayList<String>();
		Set<String> includedBills = new HashSet<String>();
		
		// Include the top bills which explain the legislator's grade 
		if (grade.equals("A") || grade.equals("B"))
			includeBillsByGrade(leg, billMsgs, includedBills, 20, false);
		else if (grade.equals("C")) {
			includeBillsByGrade(leg, billMsgs, includedBills, 13, false);
			includeBillsByGrade(leg, billMsgs, includedBills, 7, true);
		} else if (grade.equals("D")) {
			includeBillsByGrade(leg, billMsgs, includedBills, 7, false);
			includeBillsByGrade(leg, billMsgs, includedBills, 13, true);
		} else
			includeBillsByGrade(leg, billMsgs, includedBills, 20, true);
		
		// Include the top bills which explain the legislator's top scoring issues.
		if (grade.equals("A") || grade.equals("B"))
			includeBillsByTopIssues(leg, stats, billMsgs, includedBills, 3, false);
		else if (grade.equals("C")) {
			includeBillsByTopIssues(leg, stats, billMsgs, includedBills, 2, false);
			includeBillsByTopIssues(leg, stats, billMsgs, includedBills, 1, true);
		} else if (grade.equals("D")) {
			includeBillsByTopIssues(leg, stats, billMsgs, includedBills, 1, false);
			includeBillsByTopIssues(leg, stats, billMsgs, includedBills, 2, true);
		} else
			includeBillsByTopIssues(leg, stats, billMsgs, includedBills, 3, true);
		
		if (includedBills.size() == 0)
			return;
		
		createRequest(LegislatorInterpretation.generateId(dataset.getNamespace(), dataset.getRegularSession().getCode(), leg.getCode()), leg, LegislatorInterpretationService.getAiPrompt(dataset, leg, stats), String.join("\n", billMsgs));
	}

	private void includeBillsByTopIssues(Legislator leg, IssueStats stats, List<String> billMsgs, Set<String> includedBills, int amount, boolean ascending) {
		val dataset = data.getDataset(leg.getId());
		var multiplier = dataset.getConfig().getMultiplier();
		var issues = Arrays.asList(TrackedIssue.values()).stream().filter(i -> !i.equals(TrackedIssue.OverallBenefitToSociety));
		
		if (ascending)
			issues = issues.sorted(Comparator.comparingInt(i -> (int)Math.round(stats.getStat(i))));
		else
			issues = issues.sorted(Comparator.comparingInt((TrackedIssue i) -> (int)Math.round(stats.getStat(i))).reversed());
		
		for (val issue : issues.limit(amount).collect(Collectors.toList()))
		{
			billMsgs.add("\nLargest Contributors To \"" + issue.getName() + "\" Score:");
			
			if (stats.getLetterGrade(issue, multiplier).equals("A") || stats.getLetterGrade(issue, multiplier).equals("B"))
				includeBillsByIssue(leg, billMsgs, includedBills, issue, 20, false);
			else if (stats.getLetterGrade(issue, multiplier).equals("C")) {
				includeBillsByIssue(leg, billMsgs, includedBills, issue, 10, false);
				includeBillsByIssue(leg, billMsgs, includedBills, issue, 10, true);
			} else if (stats.getLetterGrade(issue, multiplier).equals("D")) {
				includeBillsByIssue(leg, billMsgs, includedBills, issue, 13, true);
				includeBillsByIssue(leg, billMsgs, includedBills, issue, 7, false);
			} else
				includeBillsByIssue(leg, billMsgs, includedBills, issue, 20, true);
		}
	}

	private void includeBillsByIssue(final Legislator leg, List<String> billMsgs, Set<String> includedBills, final TrackedIssue issue, int amount, boolean ascending) {
		var interacts = legInterp.getInteractionsForInterpretation(leg).stream().filter(i -> i.getIssueStats() != null && i.getIssueStats().hasStat(issue));
		if (ascending)
			interacts = interacts.sorted(Comparator.comparingInt(i -> Math.round(i.getIssueStats().getStat(issue) + i.getStatusProgress()*25f*i.getJudgementWeight())));
//			interacts = interacts.sorted(Comparator.comparingInt(i -> i.getImpact(issue)));
		else
			interacts = interacts.sorted(Comparator.comparingInt((LegislatorBillInteraction i) -> Math.round(i.getIssueStats().getStat(issue) + i.getStatusProgress()*25f*i.getJudgementWeight())).reversed());
//			interacts = interacts.sorted(Comparator.comparingInt((LegislatorBillInteraction i) -> i.getImpact(issue)).reversed());
		
		for (val interact : interacts.limit(amount).collect(Collectors.toList()))
		{
			val bill = data.get(interact.getBillId(), Bill.class).orElseThrow();
			
			String billMsg = "- " + interact.describe() + " \"" + interact.getBillName() + "\" (" + bill.getStatus().getDescription() + ") (" + bill.getId() + "): " + interact.getShortExplain();
			
			if ( (String.join("\n", billMsgs) + "\n" + billMsg).length() < interpModel.getContextWindowStringLength() ) {
				billMsgs.add(billMsg);
				includedBills.add(interact.getBillId());
			} else {
				break;
			}
		}
	}
	
	private void includeBillsByGrade(Legislator leg, List<String> billMsgs, Set<String> includedBills, int amount, boolean ascending) {
		val dataset = data.getDataset(leg.getId());
		billMsgs.add("Legislator's " + (ascending ? "Worst" : "Best") + " Bills:");
		
		var billsByGrade = legInterp.getInteractionsForInterpretation(leg).stream().filter(i -> i.getIssueStats() != null);
		
		if (ascending)
			billsByGrade = billsByGrade.sorted(Comparator.comparingInt(LegislatorBillInteraction::getRating));
		else
			billsByGrade = billsByGrade.sorted(Comparator.comparingInt(LegislatorBillInteraction::getRating).reversed());
		
		for (val interact : billsByGrade.limit(amount).collect(Collectors.toList()))
		{
			val bill = data.get(interact.getBillId(), Bill.class).orElseThrow();
			val billMsg = "- " + interact.describe() + " \"" + interact.getBillName() + "\" (Grade: " + interact.getIssueStats().getLetterGrade(dataset.getConfig().getMultiplier()) + ") (" + bill.getStatus().getDescription() + ") (" + bill.getId() + "): " + interact.getShortExplain();
			if ( (String.join("\n", billMsgs) + "\n" + billMsg).length() < interpModel.getContextWindowStringLength() ) {
				billMsgs.add(billMsg);
				includedBills.add(interact.getBillId());
			} else {
				break;
			}
		}
	}

	private void includeBillsByImpact(Legislator leg, List<String> billMsgs, Set<String> includedBills, int amount, boolean ascending) {
		val dataset = data.getDataset(leg.getId());
		var billsByImpact = legInterp.getInteractionsForInterpretation(leg).stream().filter(i -> i.getIssueStats() != null);
		
		if (ascending)
			billsByImpact = billsByImpact.sorted(Comparator.comparing(LegislatorBillInteraction::getOverallImpact));
		else
			billsByImpact = billsByImpact.sorted(Comparator.comparing(LegislatorBillInteraction::getOverallImpact).reversed());
		
		for (val interact : billsByImpact.limit(amount).collect(Collectors.toList()))
		{
			val bill = data.get(interact.getBillId(), Bill.class).orElseThrow();
			val billMsg = "- " + interact.describe() + " \"" + interact.getBillName() + "\" (Grade: " + interact.getIssueStats().getLetterGrade(dataset.getConfig().getMultiplier()) + ") (" + bill.getStatus().getDescription() + "): " + interact.getShortExplain();
			if ( (String.join("\n", billMsgs) + "\n" + billMsg).length() < interpModel.getContextWindowStringLength() ) {
				billMsgs.add(billMsg);
				includedBills.add(interact.getBillId());
			} else {
				break;
			}
		}
	}

	private void createRequest(String oid, Legislator leg, String sysMsg, String userMsg) {
		if (userMsg.length() >= interpModel.getContextWindowStringLength()) {
	      throw new RuntimeException("Max user message length exceeded on " + oid + " (" + userMsg.length()
	          + " > " + interpModel.getContextWindowStringLength());
	    }
		
		requests.add(InterpretationRequest.builder()
		        .data(new CustomData(oid))
		        .systemMsg(sysMsg)
		        .userMsg(userMsg)
		        .requestedModel(interpModel)
		        .reasoningEffort(ReasoningEffort.MEDIUM)
		        .build());
		
		report.interpretedLegislators.add(leg);
	}
	
	public static File requestFile(int blockNum) {
		return new File(Environment.getDeployedPath(), "openapi-legislators-bulk-" + blockNum + ".jsonl");
	}
	
	@Override
    public int run(String... args) throws Exception {
        process(data.getBuildDatasets(), new BuildReport());
        
        Quarkus.waitForExit();
        return 0;
    }
	
	public static void main(String[] args) {
		Quarkus.run(BatchLegislatorRequestGenerator.class, args);
		Quarkus.asyncExit(0);
	}
}
