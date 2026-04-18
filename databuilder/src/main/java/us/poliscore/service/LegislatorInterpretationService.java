package us.poliscore.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.DoubleIssueStats;
import us.poliscore.model.IssueStats;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.StructuralStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.VoteStatus;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.StructuralAnalysis;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorBillInteraction;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillCosponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillVote;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.service.storage.LocalCachedS3Service;

/**
 * TODO :
 * 1. Polling data.
 * 2. Make these analysis "system readable". I'd love to display "re-election chances" somewhere
 */
@ApplicationScoped
public class LegislatorInterpretationService
{
	// We don't consider a legislator valid for interpretation unless they have at least this many interactions
	public static final int LEG_INTERP_MIN_INTERACTIONS = 30;
	
	// Ensure that the x most recent bills are interpreted
	public static final int LIMIT_BILLS = 999999;
	
//	private static final String PROMPT_TEMPLATE = "The provided text is a summary of the last {{time_period}} of legislative history of United States Legislator {{full_name}}. Please generate a concise (single paragraph) critique of this history, evaluating the performance, highlighting any specific accomplishments or alarming behavior and pointing out major focuses and priorities of the legislator. In your critique, please attempt to reference concrete, notable and specific text of the summarized bills where possible.";
	
	private static final String PROMPT_TEMPLATE = """
You are part of an independent U.S. legislative watchdog which has used AI with a non-paristan bill evaluation prompt to analyze every bill in the current legislative session. These bill analyses were aggregated up to the legislators and used to evaluate the recent performance of {{namespace}} {{politicianType}} {{fullName}}. You have opinions on policy (derived from the bill analyses) and are not afraid to voice them or take sides. You do not engage in "both sides" analyses. This legislator has received the following policy area scores (scores range from -100 to 100):

{{stats}}

Based on these scores, this legislator has received the overall letter grade: {{letterGrade}}. You will be given bill interaction summaries of this politician’s recent legislative history, grouped and sorted by their impact to the relevant policy area grades, as well as the legislators most influential bills (or laws). These bills have already been graded by our system and their grade will be listed first in the summary as a letter grade from A to F. The bill's system id will be provided to you in parentheses, and always starts with BIL/. Here is an example: (BIL/us/congress/119/hr/1).

Additionally, our system has produced aggregate statistics about the legislator's bill quality, hereto referred to as an aggregate "structural analysis". You may utilize these stats when forming narratives, however do not mention them directly in your reports. Those stats are as follows:

{{structural_stats}}

Bills shall be referenced via a markdown link syntax, where the link URL is the id [name of the bill](BIL/us/congress/119/hr/1). Do not ever use [link] as the description of your link. Bills which were not included in your bill interaction summaries may be referenced by understanding our predictable id system:
1. A bill id always starts with BIL/
2. The namespace (follows next after BIL/) for congress is always 'us/congress'. For states, it is 'us/' followed by the state code. For Colorado this is 'us/co'. For Arizona it is 'us/az'.
3. Next comes the 'session code'. For congress this is '119' for the 119th congress. '118' for the 118th congress. State legislatures are unfortunately more complicated; the number here is the legiscan session id, which you should be able to infer from the provided bill interaction summaries.
4. Lastly comes the chamber code and the bill number. For congress, the chamber code may be 'hr' (for house of representatives), 's' for senate, or sjres / hjres. For state legislatures, this is typically one of: hb, sb, hcr, etc. 
Remember: your links must STRICTLY adhere to markdown link syntax. That means [description](id).

During your internal reasoning process (do not output), you may want to start by prioritizing completeness of information. This may result in overflowing the requested report length and/or "conciseness" rules. As you progress in your reasoning process, slim the content until it adheres to the specifications, prioritizing retaining important, novel or compelling narratives and traceability from shocking claim to reputable evidence. Your final output must adhere to the following specifications (refer to this when deciding if you are finished reasoning):
1. All sections of this prompt must be filled out, and each section body must be printed with its header.
2. The long report must be exactly 6, concise paragraphs, with novel or compelling narratives and traceability from claim to evidence. The casual report should be between one and three paragraphs.
3. The references section is valid JSON and follows the specified format (more on that later)
4. All links are using the correct syntax

In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Short Report:') in your response. Do not include the section instructions in your response. Do not include the legislator's policy area grade scores and do not mention their letter grade. Your target audience is voters in the general public and may span a wide variety of educational and cultural backgrounds. Avoid using politically divisive words or phrases (such as 'social equity'). Use direct, plain language. Avoid hedging phrases (e.g., “some say,” “it could be argued”). Do not balance for its own sake; weight conclusions by evidence. When asked to provide links or research, do not ever invent them.

Reasoning Steps:
This is your first section, and it includes several steps. You are to fill out each step in your response, thinking carefully at each step. By the end of this reasoning section, you will have developed a more informed and accurate analysis which you will use to fill out the final analysis sections.

Step 1. Identify a right-wing narrative and a left-wing narrative in public discourse. What do people like about this legislator? What do they dislike? Is there any relevant news coverage or national events which may have been written about this legislator? Is the coverage positive? Negative?
Step 2. Identify the legislator's point of view. You can fetch this from social media, their official website and any official government sources. 
Step 3. Find out as much as you can about their constituents. Are they republcian, democrat, rural? Are there any large or notable industries at play? Any idea what they may want or need? Median income? Racial makeup? Any recent or relevant polling information? Any notable recent town halls? How do they feel about this legislator? 
Step 4. Identify patterns in their recent policy history, which has already been sorted and grouped for you.
Step 5. Query the web to find out the media's current coverage of this legislator. Are there any recent controversies or achievements? Take care not to introduce partisan bias into the analysis at this step.
Step 6. Identify any problematic or synergistic relationships with funders.
Step 7. Identify an over-arching narrative, in alignment with the grade they have already received. This narrative should pull-in all the information we previously referenced and should attempt to form a high-level analysis of the legislator and their activity. If the legislator received an F score, create a narrative about why this philosophy is wrong and why it's causing harm to society. If they received a D score, provide an evidence-led mostly-disapproving critique, stressing the substantial shortcomings. If they received a C score, provide a balanced view of both notable strengths and weaknesses. If they received a B score, focus on their strengths while briefly mentioning minor areas of improvement. A scores should receive glowing commendations. 

Long Report:
Generate a well-referenced report of this legislator's recent activity consisting of EXACTLY 6 paragraphs. Each paragraph must be a single paragraph block and must not exceed 120 words. If you feel additional explanation is needed, prioritize summarization over expansion. Never create additional paragraphs to add nuance. The content of your paragraphs are as follows:
1. Your first paragraph will be a high-level summary of the following: the legislator's district and consituents, the legislator's priorities over the last year, and what have they actually managed to accomplish, highlighting any specific accomplishments or alarming behavior. Write your most noteworthy and interesting information here, we want to hook the reader early.
2. Dive deep into the legislator's constituency. Include the most interesting or relevant information from your previous research.
3. Identify who typically funds their campaigns, mentioning a few big ones and highlighting overall trends. If you found any problematic or synergistic relationships with funders make sure to mention them.
4. Dive deep into what they've attempted to accomplish during the last year. Compare and contrast this legislator's policy portfolio with their stated campaign goals, and where that policy aligns (or doesn't) with the needs of their constituents. If prominent constituents or groups have commented publicly on their policy, mention that here. Refrain from listing more than ten bills here, we want overall trends and notable bills (not just a dump of bills), especially if they align with painting a larger picture or focus of the legislator’s work.
5. Document your findings from your news/web research, providing concrete links to articles where relevant.
6. Conclude by painting a picture of their impact to society, both unrealized (proposed but not law), and realized.
When referring to the legislator, please use their name, rather than "the legislator". You may use markdown to format your text, linking to concrete sources where appropriate. Bills shall be referenced via a markdown link syntax, where the link URL is the id [name of the bill](BIL/us/congress/119/hr/1). Do not ever use [link] as the description of your link. Your tone here should be professional yet approachable. We want these concepts to be easy to digest for your average person whilst also respecting the integrity of the analysis. Don't lead each paragraph with 'Impact:', 'Policy analysis:', or 'What’s the coverage?'. These paragraphs should flow like natural writing. The overall tone must track the grade; for D, use a measured, evidence-first tone that underscores major shortcomings.

Casual Report:
Your audience is the general public, written at the high-school education level. Your primary goal here is to take the insights from the long report and make them available for your average person. Begin by explaining the high level goals of the representative and a high level summary of how they attempted to achieve those goals. Then, explain all the essential logic required to understand their predicted impact to society, including any research, expert opinions, societal wisdom and/or any competing concerns which may exist. Conclude with your findings. Should be between one and three paragraphs long, depending on the complexity of their history and the topics covered (controversial topics require more evidence to support your conclusions). Do not use acronyms, such as GPO, CBO, etc. If you must use them, they must be defined. You may link to sources using markdown link syntax, where relevant. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.

Short Report:
Generate a layman's, concise, single sentence describing the primary focuses of the representative, not more than 150 characters. Should start with "Focuses on". Should not include the name of the representative. Do not include any formatting text such as stars or dashes.

References:
Your written response for this research section should consist of only a compact JSON array of references, on a single line, of the following format:
[ { "type": "MISCONDUCT | STAKEHOLDER | NEWS | FUNDER", "source": "Name of the source of the information", "date": "yyyy/mm/dd", "description": "A description of the resource", "url": "http://example.com" } ]

1. MISCONDUCT: Any misconduct events that may have happened with this legislator. This includes formal misconduct reprimands by a comittee or the legislature, as well as any misconduct which might be highlighted by official medial organizations.
2. STAKEHOLDER: Official policy objectives and narratives from the legislator's official website and/or social media which might give higher-level context into their policy decisions
3. NEWS: Any newsworthy events which might include the legislator
4. FUNDER: Find out who is funding this legislator's campaigns

+Before printing the final line, internally verify the output is a JSON array of objects; each object contains exactly the keys: type, source, date, description, url; every value is a string; type is one of MISCONDUCT | STAKEHOLDER | NEWS | FUNDER; date is formatted yyyy/mm/dd. If not, correct it until it passes.
			""";
	// Adding "non-partisan" to this prompt was considered, however it was found that adding it causes Chat GPT to add a "both sides" paragraph at the end, even on legislators with a very poor score. For that reason, it was removed, as our goal here is to help inform voters, not confuse them with "both sides" type rhetoric.
	// Adding "for the voters" was found to sometimes add a nonsense sentence at the end, i.e. "voters should consider positives and negatives... bla bla bla". It's possible Chat GPT gets scared and over-thinks things if it knows it's informing voters.
	// Even mentioning poliscore can cause AI to generate garbage like "that's why poliscore gave this legislator an a grade" and other garbage. Don't even mention Poliscore, there's no point. 
	
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	private GovernmentDataService data;

	@Inject
	private OpenAIService openAiService;
	
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
	public void backfillInteractionsFromPreviousSession(Legislator leg, PoliscoreDatasetIF prevDataset)
	{
		if (prevDataset == null) return;
		if (leg.getInteractionsAll().size() >= 1000) return;
		
		val prevLeg = prevDataset.get(Legislator.generateId(prevDataset.getNamespace(), prevDataset.getRegularSession().getCode(), leg.getCode()), Legislator.class).orElse(null);
		if (prevLeg == null) return;
		
		updateInteractionsInterp(prevLeg);
		
		val prevInteracts = prevLeg.getInteractionsAll().stream().sorted(Comparator.comparing(LegislatorBillInteraction::getDate).reversed()).iterator();
		
		int c = leg.getInteractionsAll().size();
		while (c < 1000 && prevInteracts.hasNext()) {
			val n = prevInteracts.next();
			
			if (n.getIssueStats() != null) {
				n.setRating(Math.round(n.getIssueStats().getRating() * n.getJudgementWeight() * 0.9f));
				leg.getInteractionsAll().add(n);
				c++;
			}
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

	public List<LegislatorBillInteraction> getInteractionsFirstPage(List<LegislatorBillInteraction> interactions)
	{
		return interactions.stream()
				.sorted(Comparator.comparing(LegislatorBillInteraction::getRatingAbs, Comparator.nullsLast(Integer::compareTo)).reversed())
				.limit(25)
				.collect(Collectors.toCollection(ArrayList::new));
	}
	
	/**
	 * Returns true if the legislator meets all prerequisite criteria for interpretation (i.e. at least 100 bill interactions)
	 * 
	 * @param leg
	 * @return
	 */
	public boolean meetsInterpretationPrereqs(Legislator leg)
	{
		return getInteractionsForInterpretation(leg).size() >= LEG_INTERP_MIN_INTERACTIONS;
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
	public void updateInteractionsInterp(Legislator leg)
	{
		for (val i : getInteractionsForInterpretation(leg))
		{
			val interp = s3.get(BillInterpretation.generateId(i.getBillId(), null), BillInterpretation.class);
			
			if (interp.isPresent()) {
				val bill = data.get(i.getBillId(), Bill.class).orElseThrow();
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
	
	public static String getAiPrompt(PoliscoreDatasetIF dataset, Legislator leg, IssueStats stats) {
		val grade = stats.getLetterGrade(dataset.getConfig().getMultiplier());
		
		String ns = "";
		if (leg.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			ns = "Congressional";
		else
			ns = leg.getNamespace().getDescription();
		
		List<String> structuralStatList = new ArrayList<String>();
		for (var s : StructuralAnalysis.values()) {
			structuralStatList.add(s.getDisplayName() + ": " + (Math.round(leg.getInterpretation().getStructuralStats().getStat(s)*100)) + "% of this legislator's bills " + tooltipForPillar(s));
		}
		var structural_stats = String.join("\n", structuralStatList);
		
		return PROMPT_TEMPLATE
				.replace("{{date}}", java.time.LocalDate.now().toString())
				.replace("{{namespace}}", ns)
				.replace("{{structural_stats}}", structural_stats)
				.replace("{{letterGrade}}", grade)
				.replace("{{politicianType}}", leg.getTerms().last().getChamber() == LegislativeChamber.UPPER ? "Senator" : "House Representative")
				.replace("{{fullName}}", leg.getName().getOfficial_full())
				.replace("{{stats}}", stats.toString())
				.replace("{{analysisType}}",
					    grade.equals("A") || grade.equals("B") ? "commendation"
					    : grade.equals("C") ? "mixed analysis"
					    : grade.equals("D") ? "mostly disapproving critique"
					    : "disapproving critique");
//				.replace("{{behavior}}", grade.equals("A") || grade.equals("B") ? "specific accomplishments" : (grade.equals("C") || grade.equals("D") ? "specific accomplishments or alarming behavior" : "alarming behavior"));
	}
	
	public static String tooltipForPillar(StructuralAnalysis pillar) {
	    if (StructuralAnalysis.PRECISION.equals(pillar))
	      return "accurately target causal mechanisms";
	    else if (StructuralAnalysis.EVIDENCE.equals(pillar))
	      return "are supported by credible evidence or data";
	    else if (StructuralAnalysis.FEASIBILITY.equals(pillar))
	      return "are realistic and practically implementable";
	    else if (StructuralAnalysis.BUDGET.equals(pillar))
	      return "pass a detailed budget analysis";
	    else if (StructuralAnalysis.FAIRNESS.equals(pillar))
	      return "distribute benefits and burdens fairly";
	    else if (StructuralAnalysis.GOVERNANCE.equals(pillar))
	      return "respect core civic principles";
	    else if (StructuralAnalysis.RISK.equals(pillar))
	      return "identify and mitigate potential risks";
	    else
	      return "";
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

	public void calculateImpactAndRating(Legislator leg, LegislatorInterpretation interp) {
		Map<TrackedIssue, Long> impact = new HashMap<TrackedIssue, Long>();
		StructuralStats structStats = new StructuralStats();
		
		Double rating = 0.0d;
		double total = 0;
		
		for (LegislatorBillInteraction interact : leg.getInteractions()) {
			val billInterp = s3.get(BillInterpretation.generateId(interact.getBillId(), null), BillInterpretation.class).orElse(null);
			
			if (interact.getIssueStats() != null) {
				for (TrackedIssue issue : interact.getIssueStats().getStats().keySet()) {
					val existing = impact.getOrDefault(issue, 0l);
					impact.put(issue, existing + Long.valueOf(interact.getImpact(issue)));
				}
				
				if (interact.getRating() != null) {
					rating += interact.getRating();
					total++;
				}
			}
			
			if (interact.getJudgementWeight() > 0 && billInterp != null && billInterp.getStructuralAnalysisPassFail() != null) {
				structStats = structStats.addAll(billInterp.getStructuralAnalysisPassFail());
			}
		}
		
		leg.setImpactMap(impact);
		interp.setRating(total == 0 ? 0 : (int)Math.round(rating / total));
		interp.setStructuralStats(structStats.divideByTotalSummed());
	}
	
	public void recalculateAllLegislators() {
		for(val dataset : data.getBuildDatasets())
			recalculateLegislators(dataset);
	}
	
	/**
	 * Recalculates all legislator stats and bill interactions without actually re-interpreting their activity. Saves on AI interpretation costs while
	 * still allowing stats and interactions to remain up-to-date.
	 */
	public void recalculateLegislators(PoliscoreDatasetIF dataset) {
		Log.info("Recalculating legislators for " + dataset.getDescription());
		
		for (var leg : dataset.query(Legislator.class))
		{
			updateInteractionsInterp(leg);
			
			LegislatorInterpretation interp = new LegislatorInterpretation(dataset.getNamespace(), dataset.getRegularSession().getCode(), leg.getCode(), openAiService.metadata(), null);
			val interpOp = s3.get(LegislatorInterpretation.generateId(dataset.getNamespace(), dataset.getRegularSession().getCode(), leg.getCode()), LegislatorInterpretation.class);
			
			if (interpOp.isPresent()) { interp = interpOp.get(); }
			
			// If there exists an interp from a previous session, backfill the interactions until we get to 1000
			// We're skipping data from the 118th congress because the data in the 118th congress used a very primitive prompt
			if (!dataset.getCode().equals("119") && getInteractionsForInterpretation(leg).size() < 1000) {
				// If an interpretation from this session doesn't exist, grab one from the previous session.
				var previousDataset = data.getPreviousDataset(dataset);
				
				if (previousDataset != null) {
					// Pull textual interpretation from previous session if ours doesn't exist
					if (StringUtils.isBlank(interp.getShortExplain()) || StringUtils.isBlank(interp.getLongExplain())) {
						val prevInterpOp = s3.get(LegislatorInterpretation.generateId(previousDataset.getNamespace(), previousDataset.getRegularSession().getCode(), leg.getCode()), LegislatorInterpretation.class);
						if (prevInterpOp.isPresent()) {
							if (StringUtils.isBlank(interp.getCasualExplain()))
								interp.setCasualExplain(prevInterpOp.get().getCasualExplain());
							if (StringUtils.isBlank(interp.getShortExplain()))
								interp.setShortExplain(prevInterpOp.get().getShortExplain());
							if (StringUtils.isBlank(interp.getLongExplain())) {
								interp.setLongExplain(prevInterpOp.get().getLongExplain());
								interp.setLastUpdate(prevInterpOp.get().getLastUpdate());
								interp.getMetadata().setDate(prevInterpOp.get().getMetadata().getDate());
							}
						}
					}
					
					// Pull interactions from previous session if they've been interpreted
					val prevLeg = previousDataset.get(Legislator.generateId(previousDataset.getNamespace(), previousDataset.getRegularSession().getCode(), leg.getCode()), Legislator.class).orElse(null);
					if (prevLeg != null) {
						val prevInteracts = getInteractionsForInterpretation(prevLeg).iterator();
						while (getInteractionsForInterpretation(leg).size() < 1000 && prevInteracts.hasNext()) {
							val interact = prevInteracts.next();
							val interactInterpOp = s3.get(BillInterpretation.generateId(interact.getBillId(), null), BillInterpretation.class);
							
							if (interactInterpOp.isPresent() && interactInterpOp.get().getIssueStats() != null) {
								var issueStats = interactInterpOp.get().getIssueStats();
								interact.setRating(Math.round(issueStats.getRating() * interact.getJudgementWeight() * 0.9f));
								interact.setIssueStats(issueStats);
								leg.addBillInteraction(interact);
							}
						}
					}
				}
			}
			
			val interactions = getInteractionsForInterpretation(leg);
			
			interp.setHash(calculateInterpHashCode(leg));
			
			// We don't even calculate the stats unless there's at least LEG_INTERP_MIN_INTERACTIONS. We have some integrity around here.
			if (interactions.size() >= LEG_INTERP_MIN_INTERACTIONS) {
				DoubleIssueStats stats = calculateAgregateInteractionStats(leg);
				interp.setIssueStats(stats.toIssueStats());
			} else {
				interp.setIssueStats(null);
				interp.setStructuralStats(null);
			}
			
			leg.setInteractions(interactions.stream()
					.filter(i -> i.getIssueStats() != null && i.getRating() != null)
					.sorted((a,b) -> a.getDate().compareTo(b.getDate())).collect(Collectors.toCollection(ArrayList::new)));
			leg.setInteractionsFirstPage(getInteractionsFirstPage(leg.getInteractionsAll()));
			
			leg.setInterpretation(interp);
			
			calculateImpactAndRating(leg, interp);
		}
	}
}
