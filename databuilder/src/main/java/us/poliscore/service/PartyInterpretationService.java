package us.poliscore.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.ReasoningEffort;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.DoubleIssueStats;
import us.poliscore.model.IssueStats;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.StructuralAnalysis;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.session.SessionInterpretation;
import us.poliscore.model.session.SessionInterpretation.PartyBillInteraction;
import us.poliscore.model.session.SessionInterpretation.PartyInterpretation;
import us.poliscore.service.storage.LocalCachedS3Service;

// TODO : Allow AI to reference legislators?
@ApplicationScoped
public class PartyInterpretationService {
	public static final String PROMPT_TEMPLATE = """
You are part of an independent U.S. legislative watchdog which has used AI with a non-paristan bill evaluation prompt to analyze every bill in the current legislative session. These bill analyses were aggregated up to the political parties and used to evaluate the recent performance of the {{partyName}} party for the {{session}}. You have opinions on policy (derived from the bill analyses) and are not afraid to voice them or take sides. You do not engage in "both sides" analyses. This party has received the following policy area scores (scores range from -100 to 100):

{{stats}}

Based on these scores, this party has received the overall letter grade: {{letterGrade}}. You will be given summaries of bills this party has introduced within this session, sorted by two different scoring and sorting mechanisms: rating and impact.

Rating was calculated by directly sorting the bills based on the \"Overall Benefit to Society\" metric. Impact is a metric which factors in rating, number of cosponsors, and how far the bill made it through the legislative process (i.e. laws are more important than bills). Highest and lowest rated bills can be useful for knowing what the extremes of the party are up to, versus impact is useful for knowing what the party actually found coalitions around.

These bills have already been graded by our system and their grade will be listed first in the summary as a letter grade from A to F. The bill's system id will be provided to you in parentheses, and always starts with BIL/. Here is an example: (BIL/us/congress/119/hr/1).

Bills shall be referenced via a markdown link syntax, where the link URL is the id [name of the bill](BIL/us/congress/119/hr/1). Do not ever use [link] as the description of your link. Bills which were not included in your bill interaction summaries may be referenced by understanding our predictable id system:
1. A bill id always starts with BIL/
2. The namespace (follows next after BIL/) for congress is always 'us/congress'. For states, it is 'us/' followed by the state code. For Colorado this is 'us/co'. For Arizona it is 'us/az'.
3. Next comes the 'session code'. For congress this is '119' for the 119th congress. '118' for the 118th congress. State legislatures are unfortunately more complicated; the number here is the legiscan session id, which you should be able to infer from the provided bill interaction summaries.
4. Lastly comes the chamber code and the bill number. For congress, the chamber code may be 'hr' (for house of representatives), 's' for senate, or sjres / hjres. For state legislatures, this is typically one of: hb, sb, hcr, etc. 
Remember: your links must STRICTLY adhere to markdown link syntax. That means [description](id).

During your internal reasoning process (do not output), you may want to start by prioritizing completeness of information. This may result in overflowing the requested report length and/or "conciseness" rules. As you progress in your reasoning process, slim the content until it adheres to the specifications, prioritizing retaining important, novel or compelling narratives and traceability from shocking claim to reputable evidence. Your final output must adhere to the following specifications (refer to this when deciding if you are finished reasoning):
1. All sections of this prompt must be filled out, and each section body must be printed with its header.
2. The long report must be exactly 5, concise paragraphs, with novel or compelling narratives and traceability from claim to evidence. The casual report should be between one and three paragraphs.
3. The references section is valid JSON and follows the specified format (more on that later)
4. All links are using the correct syntax

In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Short Report:') in your response. Do not include the section instructions in your response. Do not include the party's policy area grade scores and do not mention their letter grade. Your target audience is voters in the general public and may span a wide variety of educational and cultural backgrounds. Avoid using politically divisive words or phrases (such as 'social equity'). Use direct, plain language. Avoid hedging phrases (e.g., “some say,” “it could be argued”). Do not balance for its own sake; weight conclusions by evidence. When asked to provide links or research, do not ever invent them.

Reasoning Steps:
This is your first section, and it includes several steps. You are to fill out each step in your response, thinking carefully at each step. By the end of this reasoning section, you will have developed a more informed and accurate analysis which you will use to fill out the final analysis sections.

Step 1. Identify a right-wing narrative and a left-wing narrative in public discourse. What do people like about this party? What do they dislike? Is there any relevant news coverage or national events which may have been written about this party? Is the coverage positive? Negative?
Step 2. Identify the party's point of view. You can fetch this from social media, their official website and any official government sources. 
Step 3. Identify patterns in their recent policy history, which has already been sorted and grouped for you.
Step 4. Query the web to find out the media's current coverage of this party. Are there any recent controversies or achievements? Take care not to introduce partisan bias into the analysis at this step.
Step 5. Identify any problematic or synergistic relationships with funders.
Step 6. Identify an over-arching narrative, in alignment with the grade they have already received. This narrative should pull-in all the information we previously referenced and should attempt to form a high-level analysis of the party and their activity. If the party received an F score, create a narrative about why this philosophy is wrong and why it's causing harm to society. If they received a D score, provide an evidence-led mostly-disapproving critique, stressing the substantial shortcomings. If they received a C score, provide a balanced view of both notable strengths and weaknesses. If they received a B score, focus on their strengths while briefly mentioning minor areas of improvement. A scores should receive glowing commendations.

Long Report:
Generate a {{analysisType}} consisting of EXACTLY 5 paragraphs. Each paragraph must be a single paragraph block and must not exceed 120 words. If you feel additional explanation is needed, prioritize summarization over expansion. Never create additional paragraphs to add nuance. The content of your paragraphs are as follows:
1. Your first paragraph will be a high level summary of the party's recent work. This summary needs to condense all the research you’ve previously done into easy, digestable talking points, pointing out major focuses, priorities and values of the party and highlighting any {{behavior}}. Mention any higher level philosophies or groups that they may subscribe or belong to. Write your most noteworthy and interesting information here, we want to hook the reader early. 
2. Document your findings from your news/web research, providing concrete links to articles where relevant.
3. Identify who typically funds their campaigns, mentioning a few big ones and highlighting overall trends. If you found any problematic or synergistic relationships with funders make sure to mention them.
4. Illuminate exactly why this party received the scores they did with a concrete policy analysis. Mention how the policy aligns with their stated campaign goals, and where that policy aligns (or doesn't) with the needs of their constituents. If prominent constituents or groups have commented publicly on their policy, mention that here. Refrain from listing more than ten bills here, we want overall trends and notable bills (not just a dump of bills), especially if they align with painting a larger picture or focus of the party's work.
5. Conclude by painting a picture of their impact to society, both unrealized (proposed but not law), and realized.
When referring to the party, please use their name, rather than "the party". You may use markdown to format your text, linking to concrete sources where appropriate. Bills shall be referenced via a markdown link syntax, where the link URL is the id [name of the bill](BIL/us/congress/119/hr/1). Do not ever use [link] as the description of your link. Your tone here should be professional yet approachable. We want these concepts to be easy to digest for your average person whilst also respecting the integrity of the analysis. Don't lead each paragraph with 'Impact:', 'Policy analysis:', or 'What’s the coverage?'. These paragraphs should flow like natural writing. The overall tone must track the grade; for D, use a measured, evidence-first tone that underscores major shortcomings.
Before printing this line, internally verify that all your links are using the correct syntax.

Casual Report:
Your audience is the general public, written at the high-school education level. Your primary goal here is to take the insights from the long report and make them available for your average person. Begin by explaining the high level goals of the party and a high level summary of how they attempted to achieve those goals. Then, explain all the essential logic required to understand their predicted impact to society, including any research, expert opinions, societal wisdom and/or any competing concerns which may exist. Conclude with your findings. Should be between one and three paragraphs long, depending on the complexity of their history and the topics covered (controversial topics require more evidence to support your conclusions). Do not use acronyms, such as GPO, CBO, etc. If you must use them, they must be defined. You may link to sources using markdown link syntax, where relevant. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.

Short Report:
Generate a layman's, concise, single sentence describing the primary focuses of the party, not more than 150 characters. Should start with "Focuses on". Should not include the name of the party. Do not include any formatting text such as stars or dashes.

References:
Your written response for this research section should consist of only a compact JSON array of references, on a single line, of the following format:
[ { "type": "MISCONDUCT | STAKEHOLDER | NEWS | FUNDER", "source": "Name of the source of the information", "date": "yyyy/mm/dd", "description": "A description of the resource", "url": "http://example.com" } ]

1. MISCONDUCT: Any misconduct events that may have happened with this party. This includes formal misconduct reprimands by a comittee or the legislature, as well as any misconduct which might be highlighted by official medial organizations.
2. STAKEHOLDER: Official policy objectives and narratives from the party's official website and/or social media which might give higher-level context into their policy decisions
3. NEWS: Any newsworthy events which might include the party
4. FUNDER: Find out who is funding this party's campaigns

Before printing the final line, internally verify the output is a JSON array of objects; each object contains exactly the keys: type, source, date, description, url; every value is a string; type is one of MISCONDUCT | STAKEHOLDER | NEWS | FUNDER; date is formatted yyyy/mm/dd. If not, correct it until it passes.
			""";
	
	public static final OpenAIModel partyInterpModel = OpenAIModel.DEFAULT_MODEL;
	
	@Inject
	private LocalCachedS3Service s3;

	@Inject
	private BillService billService;
	
	@Inject
	private GovernmentDataService data;

	@Inject
	private OpenAIService openAiService;
	
	@Inject
    ObjectMapper mapper;
	
	private final List<InterpretationRequest> requests = new ArrayList<InterpretationRequest>();
	
	private HashMap<Party, Map<TrackedIssue, PriorityQueue<PartyBillInteraction>>> bestBillsByIssue;
	
	private HashMap<Party, Map<TrackedIssue, PriorityQueue<PartyBillInteraction>>> worstBillsByIssue;
	
	public void recalculateDatasets(List<PoliscoreDatasetIF> buildDatasets) {
		for (var dataset : buildDatasets) {
			if (dataset.getCode().equals("118")) continue;
			
			var newInterp = recalculateStats(dataset);
			
			// If there's an existing interp, copy the values over
			val oldInterp = getLatestInterp(dataset);
			if (oldInterp != null) {
				newInterp.setMetadata(oldInterp.getMetadata());
				
				for (var party : Party.values()) {
					if (party != Party.INDEPENDENT || dataset.hasIndependentPartyMembers()) {
						newInterp.getPartyInterp(party).setLongExplain(oldInterp.getPartyInterp(party).getLongExplain());
						newInterp.getPartyInterp(party).setShortExplain(oldInterp.getPartyInterp(party).getShortExplain());
						newInterp.getPartyInterp(party).setReasoning(oldInterp.getPartyInterp(party).getReasoning());
						newInterp.getPartyInterp(party).setCasualExplain(oldInterp.getPartyInterp(party).getCasualExplain());
						newInterp.getPartyInterp(party).setReferences(oldInterp.getPartyInterp(party).getReferences());
					}
				}
			}
			
			if (newInterp.isComplete(dataset.hasIndependentPartyMembers())) {
				dataset.put(newInterp);
			}
		}
	}
	
	protected SessionInterpretation getLatestInterp(PoliscoreDatasetIF dataset) {
    	val namespace = dataset.getNamespace();
    	val session = dataset.getRegularSession();
    	
    	val op = s3.get(SessionInterpretation.generateId(namespace, session.getCode()), SessionInterpretation.class);
    	
    	if (op.isEmpty()) {
    		for (var loopSes : SessionInfoService.getSessions().stream()
    				.filter(loopSes -> !loopSes.equals(session) && loopSes.isRegular() && loopSes.getNamespace().equals(session.getNamespace()))
    				.sorted(Comparator.comparing(LegislativeSession::getEndDate).reversed())
    				.toList()) {
    			val op2 = s3.get(SessionInterpretation.generateId(loopSes.getNamespace(), loopSes.getCode()), SessionInterpretation.class);
				if (op2.isPresent())
					return op2.get();
    		}
    	}
    	
    	return op.orElse(null);
    }
	
	public List<InterpretationRequest> interpret(List<PoliscoreDatasetIF> buildDatasets) throws IOException
	{
		requests.clear();

		for (val dataset : buildDatasets) {
			val hasIndependent = dataset.hasIndependentPartyMembers();
					
			val partyStats = recalculateStats(dataset, hasIndependent);
			
			// Use AI to generate explanations
			createRequest(dataset, partyStats.getDemocrat());
			createRequest(dataset, partyStats.getRepublican());
			
			if (hasIndependent)
				createRequest(dataset, partyStats.getIndependent());
		}
		
		return new ArrayList<>(requests);
	}
	
	public SessionInterpretation recalculateStats(PoliscoreDatasetIF dataset) {
		return recalculateStats(dataset, dataset.hasIndependentPartyMembers());
	}
	
	@SneakyThrows
	public SessionInterpretation recalculateStats(PoliscoreDatasetIF dataset, boolean hasIndependent)
	{
		// Initialize datastructures //
		val sessionStats = new SessionInterpretation();
		sessionStats.setSession(dataset.getRegularSession());
		val partyStats = new HashMap<Party, PartyInterpretation>();
		for(val party : Party.values()) {
			if (!hasIndependent && party.equals(Party.INDEPENDENT)) continue;
			val ps = new PartyInterpretation();
			partyStats.put(party, ps);
		}
		
		val mostImpactfulBills = new HashMap<Party, PriorityQueue<PartyBillInteraction>>();
		val leastImpactfulBills = new HashMap<Party, PriorityQueue<PartyBillInteraction>>();
		val worstBills = new HashMap<Party, PriorityQueue<PartyBillInteraction>>();
		val bestBills = new HashMap<Party, PriorityQueue<PartyBillInteraction>>();
		bestBillsByIssue = new HashMap<Party, Map<TrackedIssue, PriorityQueue<PartyBillInteraction>>>();
		worstBillsByIssue = new HashMap<Party, Map<TrackedIssue, PriorityQueue<PartyBillInteraction>>>();
		
		val doublePartyStats = new HashMap<Party, DoubleIssueStats>();
		val worstLegislators = new HashMap<Party, PriorityQueue<Legislator>>();
		val bestLegislators = new HashMap<Party, PriorityQueue<Legislator>>();
		for(val party : Party.values()) {
			if (!hasIndependent && party.equals(Party.INDEPENDENT)) continue;
			
			doublePartyStats.put(party, new DoubleIssueStats());
			
			leastImpactfulBills.put(party, new PriorityQueue<>(Comparator.comparing(PartyBillInteraction::getImpact)));
			mostImpactfulBills.put(party, new PriorityQueue<>(Comparator.comparing(PartyBillInteraction::getImpact).reversed()));
			worstBills.put(party, new PriorityQueue<>(Comparator.comparing(PartyBillInteraction::getRating)));
			bestBills.put(party, new PriorityQueue<>(Comparator.comparing(PartyBillInteraction::getRating).reversed()));
			bestLegislators.put(party, new PriorityQueue<>((a,b) -> (int) (b.getRating() - a.getRating())));
			worstLegislators.put(party, new PriorityQueue<>(Comparator.comparing(Legislator::getRating)));
			
			val bestPartyBillsByIssue = new HashMap<TrackedIssue, PriorityQueue<PartyBillInteraction>>();
			bestBillsByIssue.put(party, bestPartyBillsByIssue);
			val worstPartyBillsByIssue = new HashMap<TrackedIssue, PriorityQueue<PartyBillInteraction>>();
			worstBillsByIssue.put(party, worstPartyBillsByIssue);
			for(val issue : TrackedIssue.values())
			{
				bestPartyBillsByIssue.put(issue, new PriorityQueue<PartyBillInteraction>(Comparator.comparing(PartyBillInteraction::getRating).reversed()));
				worstPartyBillsByIssue.put(issue, new PriorityQueue<PartyBillInteraction>(Comparator.comparing(PartyBillInteraction::getRating)));
			}
		}
		
		// Calculate Stats //
		for (val b : dataset.query(Bill.class)) {
			val op = billService.getInterpretation(b);
			
			if (op.isPresent()) {
				val interp = op.get();
				b.setInterpretation(interp);
				
				val sponsor = dataset.get(b.getSponsor().getId(), Legislator.class).orElseThrow();
				val party = sponsor.getTerms().last().getParty();
				val partyCosponsors = b.getCosponsors().stream().filter(sp -> dataset.exists(sp.getId(), Legislator.class) && dataset.get(sp.getId(), Legislator.class).get().getParty().equals(party)).toList();
						
				val pbi = new PartyBillInteraction(b.getId(), b.getName(), b.getStatus(), b.getType(), b.getIntroducedDate(), b.getSponsor(), partyCosponsors, b.getRating(), b.getImpact(), b.getInterpretation().getIssueStats().getLetterGrade(dataset.getConfig().getMultiplier()), interp.getShortExplain());
				mostImpactfulBills.get(party).add(pbi);
				leastImpactfulBills.get(party).add(pbi);
				bestBills.get(party).add(pbi);
				worstBills.get(party).add(pbi);
				
				for(val issue : TrackedIssue.values())
				{
					var issuePbi = new PartyBillInteraction(b.getId(), b.getName(), b.getStatus(), b.getType(), b.getIntroducedDate(), b.getSponsor(), partyCosponsors, interp.getIssueStats().getStat(issue), b.getImpact(issue), b.getInterpretation().getIssueStats().getLetterGrade(dataset.getConfig().getMultiplier()), interp.getShortExplain());
					bestBillsByIssue.get(party).get(issue).offer(issuePbi);
					worstBillsByIssue.get(party).get(issue).offer(issuePbi);
				}
				
				val ps = doublePartyStats.get(party);
				doublePartyStats.put(party, ps.sum(interp.getIssueStats().toDoubleIssueStats()));
				
				if (interp.getStructuralAnalysisPassFail() != null)
					partyStats.get(party).setStructuralStats(partyStats.get(party).getStructuralStats().addAll(interp.getStructuralAnalysisPassFail()));
			}
		}
		for (val l : dataset.query(Legislator.class)) {
			var lclone = mapper.readValue(mapper.writeValueAsString(l), Legislator.class);
			val legInterp = lclone.getInterpretation();
			
			if (legInterp != null && legInterp.getRating() != null) {
				val party = lclone.getParty();
				
				lclone.clearInteractions();
				
				val t = lclone.getTerms().last();
				lclone.getTerms().clear();
				lclone.getTerms().add(t);
				
				bestLegislators.get(party).add(lclone);
				worstLegislators.get(party).add(lclone);
				
				// Party stats are better if they're simply an aggregate of the bills
//				val ps = doublePartyStats.get(party);
//				doublePartyStats.put(party, ps.sum(legInterp.getIssueStats().toDoubleIssueStats()));
			}
		}
		
		// Build persistant data //
		for(val party : Party.values()) {
			if (!hasIndependent && party.equals(Party.INDEPENDENT)) continue;
			
			val ps = partyStats.get(party);
			
			var stats = doublePartyStats.get(party);
			stats = stats.divideByTotalSummed();
			ps.setStats(stats.toIssueStats());
			
			ps.setParty(party);
			
			for (int i = 0; i < 20; ++i) {
				if (!mostImpactfulBills.get(party).isEmpty()) ps.getMostImportantBills().add(mostImpactfulBills.get(party).poll());
				if (!leastImpactfulBills.get(party).isEmpty()) ps.getLeastImportantBills().add(leastImpactfulBills.get(party).poll());
				if (!bestBills.get(party).isEmpty()) ps.getBestBills().add(bestBills.get(party).poll());
				if (!worstBills.get(party).isEmpty()) ps.getWorstBills().add(worstBills.get(party).poll());
				if (!bestLegislators.get(party).isEmpty()) ps.getBestLegislators().add(bestLegislators.get(party).poll());
				if (!worstLegislators.get(party).isEmpty()) ps.getWorstLegislators().add(worstLegislators.get(party).poll());
			}
			
			ps.setStructuralStats(ps.getStructuralStats().divideByTotalSummed());
		}
		
		sessionStats.setDemocrat(partyStats.get(Party.DEMOCRAT));
		sessionStats.setRepublican(partyStats.get(Party.REPUBLICAN));
		
		if (hasIndependent)
			sessionStats.setIndependent(partyStats.get(Party.INDEPENDENT));
		
		sessionStats.setMetadata(openAiService.metadata());
		
//		val op = s3.get(sessionStats.getId(), SessionInterpretation.class);
//		if (op.isPresent()) {
//			sessionStats.getDemocrat().setLongExplain(op.get().getDemocrat().getLongExplain());
//			sessionStats.getRepublican().setLongExplain(op.get().getRepublican().getLongExplain());
//			sessionStats.getIndependent().setLongExplain(op.get().getIndependent().getLongExplain());
//		}
//		ddb.put(sessionStats);
		
		return sessionStats;
	}
	
	private void createRequest(PoliscoreDatasetIF dataset, PartyInterpretation interp)
	{
		List<String> msg = new ArrayList<String>();
		
		msg.add("Highest Impact Bills:");
		msg.add(StringUtils.join(interp.getMostImportantBills().stream().map(i -> i.getShortExplainForInterp()).toArray(), "\n"));
		
		msg.add("\n");
		
		val grade = interp.getStats().getLetterGrade(1.0f);
		if (grade.equals("A") || grade.equals("B")) {
			msg.add("Highest \"Overall Benefit to Society\" (Rating) Bills:");
			msg.add(StringUtils.join(interp.getBestBills().stream().limit(5).map(i -> i.getShortExplainForInterp()).toArray(), "\n"));
		} else if (grade.equals("C") || grade.equals("D")) {
			msg.add("Highest \"Overall Benefit to Society\" (Rating) Bills:");
			msg.add(StringUtils.join(interp.getBestBills().stream().limit(5).map(i -> i.getShortExplainForInterp()).toArray(), "\n"));
			msg.add("Lowest \"Overall Benefit to Society\" (Rating) Bills:");
			msg.add(StringUtils.join(interp.getWorstBills().stream().limit(5).map(i -> i.getShortExplainForInterp()).toArray(), "\n"));
		} else {
			msg.add("Lowest \"Overall Benefit to Society\" (Rating) Bills:");
			msg.add(StringUtils.join(interp.getWorstBills().stream().limit(5).map(i -> i.getShortExplainForInterp()).toArray(), "\n"));
		}
		
		for(val issue : getHighlightPolicyAreas(interp.getStats()))
		{
			msg.add("\nLargest Contributors To \"" + issue.getName() + "\" Score:\n");
			
			if (interp.getStats().getStat(issue) >= 0)
				msg.add(StringUtils.join(queueTake(10, bestBillsByIssue.get(interp.getParty()).get(issue)).stream().map(i -> i.getShortExplainForInterp()).toArray(), "\n"));
			else
				msg.add(StringUtils.join(queueTake(10, worstBillsByIssue.get(interp.getParty()).get(issue)).stream().map(i -> i.getShortExplainForInterp()).toArray(), "\n"));
		}
		
		createRequest(dataset.getRegularSession().getKey(), interp.getParty(), PartyInterpretationService.getAiPrompt(dataset, dataset.getRegularSession(), interp.getParty(), interp.getStats()), StringUtils.join(msg, "\n"));
	}
	
	private List<PartyBillInteraction> queueTake(int amt, PriorityQueue<PartyBillInteraction> queue)
	{
		List<PartyBillInteraction> result = new ArrayList<PartyBillInteraction>();
		
		while (!queue.isEmpty() && result.size() < 10)
		{
			result.add(queue.poll());
		}
		
		return result;
	}
	
	private void createRequest(String sessionKey, Party party, String sysMsg, String userMsg) {
		if (userMsg.length() >= partyInterpModel.getContextWindowStringLength()) {
			throw new RuntimeException("Max user message length exceeded on " + party.getName() + " (" + userMsg.length() + " > " + partyInterpModel.getContextWindowStringLength());
		}
		
		requests.add(InterpretationRequest.builder()
		        .data(new CustomData(SessionInterpretation.ID_CLASS_PREFIX + "/" + sessionKey + "/" + party.name()))
		        .systemMsg(sysMsg)
		        .userMsg(userMsg)
		        .requestedModel(partyInterpModel)
		        .reasoningEffort(ReasoningEffort.MEDIUM)
		        .build());
	}
	
	public static List<TrackedIssue> getHighlightPolicyAreas(IssueStats stats)
	{
		val grade = stats.getLetterGrade(1.0f);
		List<TrackedIssue> highlightPolicyAreas;
		
		if (grade.equals("A") || grade.equals("B")) {
			highlightPolicyAreas = Arrays.asList(TrackedIssue.values()).stream()
				.filter(i -> !i.equals(TrackedIssue.OverallBenefitToSociety))
				.sorted((a,b) -> (int)Math.round(stats.getStat(b) - stats.getStat(a)))
				.limit(4)
				.collect(Collectors.toList());
		} else if (grade.equals("C") || grade.equals("D")) {
			highlightPolicyAreas = Arrays.asList(TrackedIssue.values()).stream()
				.filter(i -> !i.equals(TrackedIssue.OverallBenefitToSociety))
				.sorted((a,b) -> (int)Math.round(stats.getStat(b) - stats.getStat(a)))
				.limit(2)
				.collect(Collectors.toList());
			highlightPolicyAreas.addAll(Arrays.asList(TrackedIssue.values()).stream()
					.filter(i -> !i.equals(TrackedIssue.OverallBenefitToSociety))
					.sorted((a,b) -> (int)Math.round(stats.getStat(a) - stats.getStat(b)))
					.limit(2)
					.collect(Collectors.toList()));
		} else {
			highlightPolicyAreas = Arrays.asList(TrackedIssue.values()).stream()
					.filter(i -> !i.equals(TrackedIssue.OverallBenefitToSociety))
					.sorted((a,b) -> (int)Math.round(stats.getStat(a) - stats.getStat(b)))
					.limit(2)
					.collect(Collectors.toList());
		}
		
		return highlightPolicyAreas;
	}
	
	public static String getAiPrompt(PoliscoreDatasetIF dataset, LegislativeSession session, Party party, IssueStats stats) {
		val grade = stats.getLetterGrade(dataset.getConfig().getMultiplier());
		
		String sessionName;
		if (session.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			sessionName = String.valueOf(session.getCode()) + "th congressional session";
		else
			sessionName = String.valueOf(session.getStartDate().getYear()) + "-" + String.valueOf(session.getEndDate().getYear()) + " " + session.getNamespace().getDescription();
		
		return PROMPT_TEMPLATE
				.replace("{{partyName}}", party.getName())
				.replace("{{session}}", sessionName)
				.replace("{{stats}}", stats.toString())
				.replace("{{letterGrade}}", grade)
				.replace("{{analysisType}}", grade.equals("A") || grade.equals("B") ? "endorsement" : (grade.equals("C") || grade.equals("D") ? "mixed analysis" : "harsh critique"))
				.replace("{{behavior}}", grade.equals("A") || grade.equals("B") ? "specific accomplishments" : (grade.equals("C") || grade.equals("D") ? "specific accomplishments or alarming behaviour" : "alarming behaviour"))
				.replace("{{highlightPolicyAreas}}", String.join(", ", getHighlightPolicyAreas(stats).stream().map(ti -> ti.getName()).toList()));
	}
}
