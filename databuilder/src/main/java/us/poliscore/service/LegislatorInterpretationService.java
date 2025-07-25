package us.poliscore.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.builder.HashCodeBuilder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.model.DoubleIssueStats;
import us.poliscore.model.IssueStats;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.VoteStatus;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorBillInteraction;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillCosponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillVote;
import us.poliscore.service.storage.DynamoDbPersistenceService;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.ObjectStorageServiceIF;

@ApplicationScoped
public class LegislatorInterpretationService
{
	// Ensure that the x most recent bills are interpreted
	public static final int LIMIT_BILLS = 999999;
	
//	private static final String PROMPT_TEMPLATE = "The provided text is a summary of the last {{time_period}} of legislative history of United States Legislator {{full_name}}. Please generate a concise (single paragraph) critique of this history, evaluating the performance, highlighting any specific accomplishments or alarming behaviour and pointing out major focuses and priorities of the legislator. In your critique, please attempt to reference concrete, notable and specific text of the summarized bills where possible.";
	
	private static final String PROMPT_TEMPLATE = """
You are part of a U.S. non-partisan oversight committee which has graded the recent legislative performance of {{namespace}} {{politicianType}} {{fullName}}. This legislator has received the following policy area grades (scores range from -100 to 100):

{{stats}}

Based on these scores, this legislator has received the overall letter grade: {{letterGrade}}. You will be given bill interaction summaries of this politician’s recent legislative history, grouped sorted by their impact to the relevant policy area grades, as well as the legislators most influential bills (or laws). In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Short Report:') in your response. Do not include the section instructions in your response. Do not include the legislator's policy area grade scores and do not mention their letter grade. Your target audience is voters in the general public and may span a wide variety of educational and cultural backgrounds. Avoid using politically divisive words or phrases (such as 'social equity') as it may give the impression that your analysis was partisan. Do not include any formatting or non human-readable text.

Short Report:
Generate a layman's, concise, single sentence describing the primary focuses of the representative, not more than 150 characters. Should start with "Focuses on". Should not include the name of the representative. 

Long Report:
Generate a layman's, concise, three paragraph, {{analysisType}}, highlighting any {{behavior}}, identifying trends, referencing specific bill titles (in quotes), and pointing out major focuses and priorities of the legislator. When referring to the legislator, please use their name in this section, rather than "the legislator".
			""";
	// Adding "non-partisan" to this prompt was considered, however it was found that adding it causes Chat GPT to add a "both sides" paragraph at the end, even on legislators with a very poor score. For that reason, it was removed, as our goal here is to help inform voters, not confuse them with "both sides" type rhetoric.
	// Adding "for the voters" was found to sometimes add a nonsense sentence at the end, i.e. "voters should consider positives and negatives... bla bla bla". It's possible Chat GPT gets scared and over-thinks things if it knows it's informing voters.
	// Even mentioning poliscore can cause AI to generate garbage like "that's why poliscore gave this legislator an a grade" and other garbage. Don't even mention Poliscore, there's no point. 
	
	@Inject
	private LocalCachedS3Service s3;
	
	
	@Inject
	private DynamoDbPersistenceService ddb;
	
//	public LegislatorInterpretation getOrCreate(String legislatorId)
//	{
//		val cached = s3.get(legislatorId.replaceFirst(Legislator.ID_CLASS_PREFIX, LegislatorInterpretation.ID_CLASS_PREFIX), LegislatorInterpretation.class);
//		
//		val leg = legService.getById(legislatorId).orElseThrow();
//		populateInteractionStats(leg);
//		
//		if (cached.isPresent()) //  && calculateInterpHashCode(leg) == cached.get().getHash()
//		{
//			return cached.get();
//		}
//		else
//		{
//			val interp = interpret(leg);
//			
//			return interp;
//		}
//	}
	
	public int calculateInterpHashCode(Legislator leg)
	{
		val builder = new HashCodeBuilder();
		
		for (val interact : getInteractionsForInterpretation(leg).stream().filter(i -> i.getIssueStats() != null && !(i instanceof LegislatorBillVote
						&& (((LegislatorBillVote)i).getVoteStatus().equals(VoteStatus.NOT_VOTING) || ((LegislatorBillVote)i).getVoteStatus().equals(VoteStatus.PRESENT)))).toList()) {
			builder.append(interact.getBillId());
		}
		
		return builder.build();
	}
	
	protected int sortPriority(LegislatorBillInteraction interact) {
		if (interact instanceof LegislatorBillSponsor) return 3;
		else if (interact instanceof LegislatorBillCosponsor) return 2;
		else return 1;
	}
	
	// Backfill the interactions until we get to 1000
	public void backfillInteractionsFromPreviousSession(Legislator leg, LegislativeSession prevSession)
	{
		if (prevSession == null) return;
		
		val prevLeg = ddb.get(Legislator.generateId(prevSession.getNamespace(), prevSession.getCode(), leg.getCode()), Legislator.class).orElse(null);
		if (prevLeg == null) return;
		
		val prevInteracts = prevLeg.getInteractions().stream().sorted(Comparator.comparing(LegislatorBillInteraction::getDate).reversed()).iterator();
		while (leg.getInteractionsPrivate1().size() < 1000 && prevInteracts.hasNext()) {
			val n = prevInteracts.next();
			if (n.getIssueStats() != null)
				leg.getInteractionsPrivate1().add(n);
		}
	}
	
	public List<LegislatorBillInteraction> getInteractionsForInterpretation(Legislator leg)
	{
		return leg.getInteractions().stream()
				// Remove duplicate bill interactions, favoring sponsor and co-sponsor over vote
				.collect(Collectors.groupingBy(LegislatorBillInteraction::getBillId,Collectors.toList())).values().stream()
					.map(l -> l.size() > 1 ? l.stream().sorted((aa,bb) -> sortPriority(bb) - sortPriority(aa)).findFirst().get() : l.get(0))
				.filter(i -> isRelevant(i))
				.sorted(Comparator.comparing(LegislatorBillInteraction::getDate).reversed())
				.collect(Collectors.toList());
	}
	
	/**
	 * Returns true if the legislator meets all prerequisite criteria for interpretation (i.e. at least 100 bill interactions)
	 * 
	 * @param leg
	 * @return
	 */
	public boolean meetsInterpretationPrereqs(Legislator leg)
	{
		return getInteractionsForInterpretation(leg).size() >= 100;
	}
	
//	protected void interpretMostRecentInteractions(Legislator leg)
//	{
//		int interpretedBills = 0;
//		for (val i : getInteractionsForInterpretation(leg))
//		{
//			if (interpretedBills >= LIMIT_BILLS) break;
//			
//			try
//			{
//				val interp = billInterpreter.getOrCreate(i.getBillId());
//				
//				i.setIssueStats(interp.getIssueStats());
//				
//				interpretedBills++;
//			}
//			catch (MissingBillTextException ex)
//			{
//				// TODO
//				Log.error("Could not find text for bill " + i.getBillId());
//			}
//		}
//	}
	
	protected boolean isRelevant(LegislatorBillInteraction interact)
	{
		return !(interact instanceof LegislatorBillVote
		&& (((LegislatorBillVote)interact).getVoteStatus().equals(VoteStatus.NOT_VOTING) || ((LegislatorBillVote)interact).getVoteStatus().equals(VoteStatus.PRESENT)));
	}
	
	/**
	 * Updates all bill interactions with the latest bill interpretation from S3.
	 */
	public void updateInteractionsInterp(ObjectStorageServiceIF objStore, Legislator leg)
	{
		for (val i : getInteractionsForInterpretation(leg))
		{
			val interp = s3.get(BillInterpretation.generateId(i.getBillId(), null), BillInterpretation.class);
			
			if (interp.isPresent()) {
				val bill = objStore.get(i.getBillId(), Bill.class).orElseThrow();
				bill.setInterpretation(interp.get());
				
				i.populate(bill, interp.get());
			}
		}
	}
	
	public DoubleIssueStats calculateAgregateInteractionStats(Legislator leg) {
		DoubleIssueStats stats = new DoubleIssueStats();
		
		for (val interact : getInteractionsForInterpretation(leg))
		{
			if (interact.getIssueStats() != null)
			{
				val weightedStats = interact.getIssueStats().multiply(interact.getJudgementWeight());
				stats = stats.sum(weightedStats, Math.abs(interact.getJudgementWeight()));
			}
		}
		
		stats = stats.divideByTotalSummed();
		return stats;
	}
	
//	public Map<TrackedIssue, List<LegislatorBillInteraction>> calculateTopInteractions(Legislator leg) {
//		val result = new HashMap<TrackedIssue, List<LegislatorBillInteraction>>();
//		
//		for (val issue : TrackedIssue.values()) {
//			val list = new ArrayList<LegislatorBillInteraction>();
//			
//			for (LegislatorBillInteraction interact : getInteractionsForInterpretation(leg)) {
//				if (interact.getIssueStats() != null) {
//					list.add(interact);
//				}
//			}
//			
//			list.sort(Comparator.comparingInt(i -> Math.round(i.getRating(issue))));
//			
//			result.put(issue, list);
//		}
//		
//		return result;
//	}
	
//	protected LegislatorInterpretation interpret(Legislator leg)
//	{
//		interpretMostRecentInteractions(leg);
//		
//		populateInteractionStats(leg);
//		
//		IssueStats stats = new IssueStats();
//		
//		LocalDate periodStart = null;
//		val periodEnd = LocalDate.now();
//		List<String> billMsgs = new ArrayList<String>();
//		
//		for (val interact : getInteractionsForInterpretation(leg))
//		{
//			if (interact.getIssueStats() != null)
//			{
//				if (interact instanceof LegislatorBillVote
//						&& (((LegislatorBillVote)interact).getVoteStatus().equals(VoteStatus.NOT_VOTING) || ((LegislatorBillVote)interact).getVoteStatus().equals(VoteStatus.PRESENT)))
//					continue;
//				
//				val weightedStats = interact.getIssueStats().multiply(interact.getJudgementWeight());
//				stats = stats.sum(weightedStats, Math.abs(interact.getJudgementWeight()));
//				
//				val billMsg = interact.describe() + ": " + interact.getIssueStats().getExplanation();
//				if ( (String.join("\n", billMsgs) + "\n" + billMsg).length() < OpenAIService.MAX_SECTION_LENGTH ) {
//					billMsgs.add(billMsg);
//					periodStart = (periodStart == null) ? interact.getDate() : (periodStart.isAfter(interact.getDate()) ? interact.getDate() : periodStart);
//				}
//			}
//		}
//		
//		stats = stats.divideByTotalSummed();
//		
//		val prompt = getAiPrompt(leg, periodStart, periodEnd);
//		System.out.println(prompt);
//		System.out.println(String.join("\n", billMsgs));
//		val interpText = ai.chat(prompt, String.join("\n", billMsgs));
//		stats.setExplanation(interpText);
//		
//		val interp = new LegislatorInterpretation(OpenAIService.metadata(), leg, stats);
//		interp.setHash(calculateInterpHashCode(leg));
//		s3.put(interp);
//		
//		leg.setInterpretation(interp);
//		
//		memService.put(leg);
//		
//		return interp;
//	}
	
	public static String getAiPrompt(Legislator leg, IssueStats stats) {
		val grade = stats.getLetterGrade();
		
		String ns = "";
		if (leg.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			ns = "Congressional";
		else
			ns = leg.getNamespace().getDescription() + " State";
		
		return PROMPT_TEMPLATE
				.replace("{{namespace}}", ns)
				.replace("{{letterGrade}}", grade)
				.replace("{{politicianType}}", leg.getTerms().last().getChamber() == LegislativeChamber.UPPER ? "Senator" : "House Representative")
				.replace("{{fullName}}", leg.getName().getOfficial_full())
				.replace("{{stats}}", stats.toString())
				.replace("{{analysisType}}", grade.equals("A") || grade.equals("B") ? "commendation" : (grade.equals("C") || grade.equals("D") ? "mixed analysis" : "harsh critique"))
				.replace("{{behavior}}", grade.equals("A") || grade.equals("B") ? "specific accomplishments" : (grade.equals("C") || grade.equals("D") ? "specific accomplishments or alarming behaviour" : "alarming behaviour"));
	}
	
	public static String describeTimePeriod(LocalDate periodStart, LocalDate periodEnd)
	{
		if (periodStart == null || periodEnd == null) return "several months";
		
		long dayDiff = ChronoUnit.DAYS.between(periodStart, periodEnd);
	    
	    if (dayDiff < 30)
	    {
	    	return dayDiff + " days";
	    }
	    else if (dayDiff < (30 * 11))
	    {
	    	int months = Math.round((float)dayDiff / 30f);
	    	
	    	return months + " month" + (months <= 1 ? "" : "s");
	    }
	    else
	    {
	    	int years = Math.round((float)dayDiff / 365f);
	    	
	    	return years + " year" + (years <= 1 ? "" : "s");
	    }
	}

	public void calculateImpact(Legislator leg) {
		Map<TrackedIssue, Long> impact = new HashMap<TrackedIssue, Long>();
		
		for (LegislatorBillInteraction interact : getInteractionsForInterpretation(leg)) {
			if (interact.getIssueStats() != null) {
				for (TrackedIssue issue : interact.getIssueStats().getStats().keySet()) {
					val existing = impact.getOrDefault(issue, 0l);
					impact.put(issue, existing + Long.valueOf(interact.getImpact(issue)));
				}
			}
		}
		
		leg.setImpactMap(impact);
	}
}
