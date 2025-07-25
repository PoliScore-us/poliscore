package us.poliscore.entrypoint.batch;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreUtil;
import us.poliscore.ai.BatchOpenAIRequest;
import us.poliscore.ai.BatchOpenAIRequest.BatchBillMessage;
import us.poliscore.ai.BatchOpenAIRequest.BatchOpenAIBody;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.model.DoubleIssueStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorBillInteraction;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorInterpretationService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.storage.S3PersistenceService;

/**
 * Generates a bulk request to open ai for all legislators 
 */
@QuarkusMain(name="BatchLegislatorRequestGenerator")
public class BatchLegislatorRequestGenerator implements QuarkusApplication
{
	public static final long TOKEN_BLOCK_SIZE = 30000000;
	
	public static final boolean CHECK_S3_EXISTS = true;
	
	@Inject
	private S3PersistenceService s3;
	
	@Inject
	private GovernmentDataService data;
	
	@Inject
	private LegislatorInterpretationService legInterp;
	
	private long tokenLen = 0;
	
	private long totalRequests = 0;
	
	private long skipped = 0;
	
	private List<BatchOpenAIRequest> requests = new ArrayList<BatchOpenAIRequest>();
	
	private List<File> writtenFiles = new ArrayList<File>();
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	public List<File> process(List<PoliscoreDataset> buildDatasets) throws IOException
	{
		Log.info("Generating batch request to interpret legislators");
		
		data.importAllDatasets();
		
		int block = 1;
		
//		s3.optimizeExists(LegislatorInterpretation.class);
		
		for (PoliscoreDataset dataset : buildDatasets) {
			for (Legislator l : dataset.query(Legislator.class).stream()
					.filter(l -> 
						l.getInteractions().size() > 0
	//					&& (l.getBioguideId().equals("H000273"))
						&& (!CHECK_S3_EXISTS || !s3.exists(LegislatorInterpretation.generateId(dataset.getSession().getNamespace(), dataset.getSession().getCode(), l.getCode()), LegislatorInterpretation.class))
					)
					.sorted(Comparator.comparing(Legislator::getDate).reversed())
	//				.limit(1)
					.toList()) {
				interpret(dataset, l);
				
				if (tokenLen >= TOKEN_BLOCK_SIZE) {
					writeBlock(block++);
				}
			};
		}
		
		writeBlock(block++);
		
		Log.info("Batch legislator request generator complete. Generated " + totalRequests + " requests. Skipped " + skipped + " legislators.");
		
		return writtenFiles;
	}
	
	protected void interpret(PoliscoreDataset dataset, Legislator leg)
	{
		legInterp.backfillInteractionsFromPreviousSession(leg, data.getPreviousSession(dataset.getSession()));
		
		if (!legInterp.meetsInterpretationPrereqs(leg))
		{
			Log.info("Skipping " + leg.getId() + " (" + leg.getName().getOfficial_full() + ") because he did not have at least 100 interactions.");
			skipped++;
			return;
		}
		
		legInterp.updateInteractionsInterp(data.getAllDataset(), leg);
		
		DoubleIssueStats stats = legInterp.calculateAgregateInteractionStats(leg);
		
		List<String> billMsgs = new ArrayList<String>();
		Set<String> includedBills = new HashSet<String>();
		
		// Include the top bills which explain the legislator's grade 
		if (stats.getLetterGrade().equals("A") || stats.getLetterGrade().equals("B"))
			includeBillsByGrade(dataset, leg, billMsgs, includedBills, 20, false);
		else if (stats.getLetterGrade().equals("C")) {
			includeBillsByGrade(dataset, leg, billMsgs, includedBills, 13, false);
			includeBillsByGrade(dataset, leg, billMsgs, includedBills, 7, true);
		} else if (stats.getLetterGrade().equals("D")) {
			includeBillsByGrade(dataset, leg, billMsgs, includedBills, 7, false);
			includeBillsByGrade(dataset, leg, billMsgs, includedBills, 13, true);
		} else
			includeBillsByGrade(dataset, leg, billMsgs, includedBills, 20, true);
		
		// Include the top bills which explain the legislator's top scoring issues.
		if (stats.getLetterGrade().equals("A") || stats.getLetterGrade().equals("B"))
			includeBillsByTopIssues(dataset, leg, stats, billMsgs, includedBills, 3, false);
		else if (stats.getLetterGrade().equals("C")) {
			includeBillsByTopIssues(dataset, leg, stats, billMsgs, includedBills, 2, false);
			includeBillsByTopIssues(dataset, leg, stats, billMsgs, includedBills, 1, true);
		} else if (stats.getLetterGrade().equals("D")) {
			includeBillsByTopIssues(dataset, leg, stats, billMsgs, includedBills, 1, false);
			includeBillsByTopIssues(dataset, leg, stats, billMsgs, includedBills, 2, true);
		} else
			includeBillsByTopIssues(dataset, leg, stats, billMsgs, includedBills, 3, true);
		
		if (includedBills.size() == 0)
			return;
		
		createRequest(LegislatorInterpretation.generateId(dataset.getSession().getNamespace(), dataset.getSession().getCode(), leg.getCode()), LegislatorInterpretationService.getAiPrompt(leg, stats.toIssueStats()), String.join("\n", billMsgs));
	}

	private void includeBillsByTopIssues(PoliscoreDataset dataset, Legislator leg, DoubleIssueStats stats, List<String> billMsgs, Set<String> includedBills, int amount, boolean ascending) {
		
		var issues = Arrays.asList(TrackedIssue.values()).stream().filter(i -> !i.equals(TrackedIssue.OverallBenefitToSociety));
		
		if (ascending)
			issues = issues.sorted(Comparator.comparingInt(i -> (int)Math.round(stats.getStat(i))));
		else
			issues = issues.sorted(Comparator.comparingInt((TrackedIssue i) -> (int)Math.round(stats.getStat(i))).reversed());
		
		for (val issue : issues.limit(amount).collect(Collectors.toList()))
		{
			billMsgs.add("\nLargest Contributors To \"" + issue.getName() + "\" Score:");
			
			if (stats.getLetterGrade(issue).equals("A") || stats.getLetterGrade(issue).equals("B"))
				includeBillsByIssue(dataset, leg, billMsgs, includedBills, issue, 20, false);
			else if (stats.getLetterGrade(issue).equals("C")) {
				includeBillsByIssue(dataset, leg, billMsgs, includedBills, issue, 10, false);
				includeBillsByIssue(dataset, leg, billMsgs, includedBills, issue, 10, true);
			} else if (stats.getLetterGrade(issue).equals("D")) {
				includeBillsByIssue(dataset, leg, billMsgs, includedBills, issue, 13, true);
				includeBillsByIssue(dataset, leg, billMsgs, includedBills, issue, 7, false);
			} else
				includeBillsByIssue(dataset, leg, billMsgs, includedBills, issue, 20, true);
		}
	}

	private void includeBillsByIssue(PoliscoreDataset dataset, final Legislator leg, List<String> billMsgs, Set<String> includedBills, final TrackedIssue issue, int amount, boolean ascending) {
		var interacts = legInterp.getInteractionsForInterpretation(leg).stream().filter(i -> i.getIssueStats() != null && i.getIssueStats().hasStat(issue));
		if (ascending)
			interacts = interacts.sorted(Comparator.comparingInt(i -> Math.round(i.getRating(issue) + i.getStatusProgress()*25f*i.getJudgementWeight())));
//			interacts = interacts.sorted(Comparator.comparingInt(i -> i.getImpact(issue)));
		else
			interacts = interacts.sorted(Comparator.comparingInt((LegislatorBillInteraction i) -> Math.round(i.getRating(issue) + i.getStatusProgress()*25f*i.getJudgementWeight())).reversed());
//			interacts = interacts.sorted(Comparator.comparingInt((LegislatorBillInteraction i) -> i.getImpact(issue)).reversed());
		
		for (val interact : interacts.limit(amount).collect(Collectors.toList()))
		{
			val bill = dataset.get(interact.getBillId(), Bill.class).orElseThrow();
			
			String billMsg = "- " + interact.describe() + " \"" + interact.getBillName() + "\" (" + bill.getStatus().getDescription() + "): " + interact.getShortExplain();
			
			if ( (String.join("\n", billMsgs) + "\n" + billMsg).length() < OpenAIService.MAX_REQUEST_LENGTH ) {
				billMsgs.add(billMsg);
				includedBills.add(interact.getBillId());
			} else {
				break;
			}
		}
	}
	
	private void includeBillsByGrade(PoliscoreDataset dataset, Legislator leg, List<String> billMsgs, Set<String> includedBills, int amount, boolean ascending) {
		billMsgs.add("Legislator's " + (ascending ? "Worst" : "Best") + " Bills:");
		
		var billsByGrade = legInterp.getInteractionsForInterpretation(leg).stream().filter(i -> i.getIssueStats() != null);
		
		if (ascending)
			billsByGrade = billsByGrade.sorted(Comparator.comparingInt(LegislatorBillInteraction::getWeightedRating));
		else
			billsByGrade = billsByGrade.sorted(Comparator.comparingInt(LegislatorBillInteraction::getWeightedRating).reversed());
		
		for (val interact : billsByGrade.limit(amount).collect(Collectors.toList()))
		{
			val bill = dataset.get(interact.getBillId(), Bill.class).orElseThrow();
			val billMsg = "- " + interact.describe() + " \"" + interact.getBillName() + "\" (" + bill.getStatus().getDescription() + "): " + interact.getShortExplain();
			if ( (String.join("\n", billMsgs) + "\n" + billMsg).length() < OpenAIService.MAX_REQUEST_LENGTH ) {
				billMsgs.add(billMsg);
				includedBills.add(interact.getBillId());
			} else {
				break;
			}
		}
	}

	private void includeBillsByImpact(PoliscoreDataset dataset, Legislator leg, List<String> billMsgs, Set<String> includedBills, int amount, boolean ascending) {
		var billsByImpact = legInterp.getInteractionsForInterpretation(leg).stream().filter(i -> i.getIssueStats() != null);
		
		if (ascending)
			billsByImpact = billsByImpact.sorted(Comparator.comparing(LegislatorBillInteraction::getOverallImpact));
		else
			billsByImpact = billsByImpact.sorted(Comparator.comparing(LegislatorBillInteraction::getOverallImpact).reversed());
		
		for (val interact : billsByImpact.limit(amount).collect(Collectors.toList()))
		{
			val bill = dataset.get(interact.getBillId(), Bill.class).orElseThrow();
			val billMsg = "- " + interact.describe() + " \"" + interact.getBillName() + "\" (" + bill.getStatus().getDescription() + "): " + interact.getShortExplain();
			if ( (String.join("\n", billMsgs) + "\n" + billMsg).length() < OpenAIService.MAX_REQUEST_LENGTH ) {
				billMsgs.add(billMsg);
				includedBills.add(interact.getBillId());
			} else {
				break;
			}
		}
	}

	private void createRequest(String oid, String sysMsg, String userMsg) {
		List<BatchBillMessage> messages = new ArrayList<BatchBillMessage>();
		messages.add(new BatchBillMessage("system", sysMsg));
		messages.add(new BatchBillMessage("user", userMsg));
		
		requests.add(new BatchOpenAIRequest(
				new CustomData(oid),
				new BatchOpenAIBody(messages)
		));
		
		tokenLen += (userMsg.length() / 4);
	}

	private void writeBlock(int block) throws IOException {
		if (requests.size() == 0) return;
		
		File f = requestFile(block);
		
		val mapper = PoliscoreUtil.getObjectMapper();
		val s = requests.stream().map(r -> {
			try {
				return mapper.writeValueAsString(r);
			} catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			}
		}).toList();
		
		FileUtils.write(f, String.join("\n", s), "UTF-8");
		
		totalRequests += requests.size();
		
		Log.info("Successfully wrote " + requests.size() + " requests to " + f.getAbsolutePath());
		
		writtenFiles.add(f);
		
		requests = new ArrayList<BatchOpenAIRequest>();
		tokenLen = 0;
	}
	
	public static File requestFile(int blockNum) {
		return new File(Environment.getDeployedPath(), "openapi-legislators-bulk-" + blockNum + ".jsonl");
	}
	
	@Override
    public int run(String... args) throws Exception {
        process(data.getBuildDatasets());
        
        Quarkus.waitForExit();
        return 0;
    }
	
	public static void main(String[] args) {
		Quarkus.run(BatchLegislatorRequestGenerator.class, args);
		Quarkus.asyncExit(0);
	}
}
