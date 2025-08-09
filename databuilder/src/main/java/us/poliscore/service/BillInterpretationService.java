package us.poliscore.service;

import java.util.Arrays;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.val;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.service.storage.LocalCachedS3Service;

/**
 * Two different interpretation strategies were tested for interpreting very large bills in "slices".
 * 
 * A) Summarize each slice and then interpret the summaries and ask AI to generate stats based on the summaries
 * B) Generate a small summary and also stats for each slice. Ask AI to summarize the summaries and then average all the stats for the final bill stats
 * 
 * These strategies were tested on BIL/us/congress/118/hr/8580 and it was determined that scenario A resulted in less accurate overall stats due to the fact
 * that each individual summary resulted in somewhat of a "telephone game" effect which resulted in a more "muted" outcome and which was less present when
 * averaging the stats from each slice.
 * 
 * Experimentations have also been made around concise versus longer responses, however the conclusion (with ChatGPT 4o) is that prompts without the "concise"
 * keyword tend to include a lot of wordy "filler" content without exposing much additional useful information from the bill. Longer form responses also suffer
 * from "header" content (the AI likes to have a paragraph for each tracked issue with a *** header *** format), however this can be avoided with a "include a
 * report without headers" phrasing.
 */
@ApplicationScoped
public class BillInterpretationService {
	
	public static final String statsPromptTemplate = """
			You will be given the text of a United States bill. Your role is to be a non-partisan oversight committee, producing an impact analysis which evaluates whether or not the following bill will produce a positive overall benefit to society. In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Impact:') in your response. Do not include the section instructions in your response. Do not ever use 'I' language (as in, I reached this conclusion because...).
			
			{searchModelInstructions1}

			Reasoning Steps:
			This is your first section, and it includes several steps. You are to fill out each step in your response, thinking carefully at each step. By the end of this reasoning section, you will have developed a more informed and accurate analysis which you will use to fill out the final analysis sections.
			
			Step 1: Initial take - Read through the bill text and write a short summary here. Analyze the mechanisms by which the bill attempts to achieve those goals. What do you think the expected outcome will be, purely based on reading the text alone? Does it make sense at a high level? Are there any glaring problems?
			Step 2: Gather references - Find similar laws that might already be on the books, identify overlap or legal context to the bill. Identify public resources - identify relevant scientific papers (if any), expert opinions, and any existing analysis or reports which might be relevant, including budgetary analysis (CBO).
			Step 3: Identify a narrative - Apply the narratives taken from legal, media and other expert opinion to the bill. Does it align with your initial take? Do any of these media organizations exhibit any bias? Can you identify an overarching narrative about the bill which seems to be true, accounting for all the evidence you now have? 
			Step 4: Budgetary concerns - Is the bill a good use of taxpayer dollars? How much might it cost over the short term? And the long term? Will the taxpayers experience a "net win", gaining more in services than they spend on the service(s)?
			Step 5: Identify winners and losers - Is the bill good for some people and bad for others? Who do you think might be behind the bill?
			Step 6: Estimate confidence and identify unknown - List any uncertainties in your analysis, questions or unknowns you might have which might change the outcome of your analysis.
			{searchModelStep}

			Impact:
			Score the following bill on the estimated impact to the United States upon the following criteria, rated from -100 (very harmful) to 0 (neutral) to +100 (very helpful) or N/A if it is not relevant. There is an important distinction between 0 and N/A. Use 0 if the goal of the bill was to provide impact in the policy area - but you are predicting that it will have none. Use N/A if the goals of the bill do not align at all with the policy area.
			
			{issuesList}
			
			Rating:
			Write a single integer, from -100 to 100, which represents how strongly you think the legislation should be passed, with -100 being it definitely should NOT be passed and 100 being it definitely SHOULD be passed. This rating should directly translate to a grade for the bill, as per the following grading rubric (where r is the rating): A: r >= 60, B: 60 < r >= 40, C: 40 < r >= 20, D: 20 < r >= 0, F: r < 0. If the impact to society is negative, then so too should the rating score - naturally you wouldn't recommend voting for a bill which is bad for society. This rating metric is however separate from the impact scores, in that a bill might be of high rating but it might have a low impact. A common example might be a congressional gold medal award given for a heroic act (high rating), but the overall impact to society is small. Respond with only the integer, no commentary or units.
			
			Bill Title:
			Write the bill title. If the bill does not have a title and is only referred to by its bill number (such as HR 4141), please make up a very concise title for the bill based on its content. If the bill has a title, but it is confusing, vague, too long, or would otherwise be poorly understood by the general public, please make up a very concise title for the bill based on its content.
			
			Short Report:
			Your audience is general public layman voters, written at the high-school education level. A single paragraph, at least four sentence report which gives a detailed, but not repetitive, summary of the bill, any high level goals, and it's expected impact to society. Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.
			
			Long Report:
			Your audience is industry expert analysts, written at the PHD research education level. Your first paragraph will explain the high level goals of the bill, give a high level summary of how it attempts to achieve those goals, and then conclude by giving your opinion on the impact the bill will have on society, if enacted. After your high-level summary, you will then go on to explain, in depth, how you came to these conclusions. You may conclude by identifying winners and losers of the bill, and identifying industry stakeholders, if relevant. Should be between three and seven paragraphs long, depending on the complexity of the bill and the topics it covers. If the bill touches on controversial topics such as trans issues or guns rights, please include the advocating logic by proponents and also the advocating logic of the opposition, otherwise do not include this logic. Where relevant, cite scientific studies or the opinions of authoritative knowledge sources to provide more context. Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Laymans Report:
			Your audience is general public layman voters, written at the high-school education or lower level. Do not make this sound like a dry confusing report, try to form an emotional connection and tie it to concrete things they care about. Use analogies and help it to make sense for your average person. You must explain the high level goals of the bill, a high level summary of how it attempts to achieve those goals, and your opinion on the impact the bill will have on society, if enacted. Should be between two and four paragraphs long, depending on the complexity of the bill and the topics it covers. Do not use ancronyms, such as GPO, CBO, etc. If you must use them, they must be defined. Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Confidence:
			A self-rated integer from 0 to 100 measuring how confident you are that your analysis was valid and interpreted correctly.
			
			{searchReferences}
			""";
	
	public static final String slicePromptTemplate = """
			You will be given the text of a United States bill. Your role is to be a non-partisan oversight committee, evaluating whether or not the following bill will produce a positive overall benefit to society. In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Impact:') in your response. Do not include the section instructions in your response. Do not ever use 'I' language (as in, I reached this conclusion because...).

			Reasoning Steps:
			This is your first section, and it includes several steps. You are to fill out each step in your response, thinking carefully at each step. By the end of this reasoning section, you will have developed a more informed and accurate analysis which you will use to fill out the final analysis sections.
			
			Step 1: Initial take - Read through the bill text and write a short summary here. Analyze the mechanisms by which the bill attempts to achieve those goals. What do you think the expected outcome will be, purely based on reading the text alone? Does it make sense at a high level? Are there any glaring problems?
			Step 2: Gather references - Find similar laws that might already be on the books, identify overlap or legal context to the bill. Identify public resources - identify relevant scientific papers (if any), expert opinions, and any existing analysis or reports which might be relevant, including budgetary analysis (CBO).
			Step 3: Identify a narrative - Apply the narratives taken from legal, media and other expert opinion to the bill. Does it align with your initial take? Do any of these media organizations exhibit any bias? Can you identify an overarching narrative about the bill which seems to be true, accounting for all the evidence you now have? 
			Step 4: Budgetary concerns - Is the bill a good use of taxpayer dollars? How much might it cost over the short term? And the long term? Will the taxpayers experience a "net win", gaining more in services than they spend on the service(s)?
			Step 5: Identify winners and losers - Is the bill good for some people and bad for others? Who do you think might be behind the bill?
			Step 6: Estimate confidence and identify unknown - List any uncertainties in your analysis, questions or unknowns you might have which might change the outcome of your analysis.
			
			Impact:
			Score the following bill on the estimated impact to the United States upon the following criteria, rated from -100 (very harmful) to 0 (neutral) to +100 (very helpful) or N/A if it is not relevant. There is an important distinction between 0 and N/A. Use 0 if the goal of the bill was to provide impact in the policy area - but you are predicting that it will have none. Use N/A if the goals of the bill do not align at all with the policy area.
			
			{issuesList}
			
			Rating:
			Write a single integer, from -100 to 100, which represents how strongly you think the legislation should be passed, with -100 being it definitely should NOT be passed and 100 being it definitely SHOULD be passed. This rating should directly translate to a grade for the bill, as per the following grading rubric (where r is the rating): A: r >= 60, B: 60 < r >= 40, C: 40 < r >= 20, D: 20 < r >= 0, F: r < 0. If the impact to society is negative, then so too should the rating score - naturally you wouldn't recommend voting for a bill which is bad for society. This rating metric is however separate from the impact scores, in that a bill might be of high rating but it might have a low impact. A common example might be a congressional gold medal award given for a heroic act (high rating), but the overall impact to society is small. Respond with only the integer, no commentary or units.
			
			Long Report:
			A detailed, but not repetitive, report of the bill which references concrete, notable and specific text of the bill where possible. Make sure to explain: an overall summary of the bill; the high level goals the bill is attempting to achieve, and how it plans to achieve those goals; the impact to society the bill would have, if enacted. Your audience here is general public layman voters, so if you think they won't understand an acronym or a complex topic, please explain it. Should be between one and four paragraphs long, depending on the complexity of the bill and the topics it covers. Where relevant, cite scientific studies or the opinions of authoritative knowledge sources to provide more context. Keep in mind that we're trying to figure out how to spend U.S. taxpayer dollars: budgetary concerns are important. Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.
			
			{searchReferences}
			""";
	
	public static final String aggregatePrompt = """
			A large U.S. bill has been split into sections and summarized. Your role is to be a non-partisan oversight committee, evaluating whether or not the following bill will produce a positive overall benefit to society. In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Impact:') in your response. Do not include the section instructions in your response. Do not ever use 'I' language (as in, I reached this conclusion because...).
			
			{searchModelInstructions1}
			
			Reasoning Steps:
			This is your first section, and it includes several steps. You are to fill out each step in your response, thinking carefully at each step. By the end of this reasoning section, you will have developed a more informed and accurate analysis which you will use to fill out the final analysis sections.
			
			Step 1: Initial take - Read through the bill text and write a short summary here. Analyze the mechanisms by which the bill attempts to achieve those goals. What do you think the expected outcome will be, purely based on reading the text alone? Does it make sense at a high level? Are there any glaring problems?
			Step 2: Gather references - Find similar laws that might already be on the books, identify overlap or legal context to the bill. Identify public resources - identify relevant scientific papers (if any), expert opinions, and any existing analysis or reports which might be relevant, including budgetary analysis (CBO).
			Step 3: Identify a narrative - Apply the narratives taken from legal, media and other expert opinion to the bill. Does it align with your initial take? Do any of these media organizations exhibit any bias? Can you identify an overarching narrative about the bill which seems to be true, accounting for all the evidence you now have? 
			Step 4: Budgetary concerns - Is the bill a good use of taxpayer dollars? How much might it cost over the short term? And the long term? Will the taxpayers experience a "net win", gaining more in services than they spend on the service(s)?
			Step 5: Identify winners and losers - Is the bill good for some people and bad for others? Who do you think might be behind the bill?
			Step 6: Estimate confidence and identify unknown - List any uncertainties in your analysis, questions or unknowns you might have which might change the outcome of your analysis.
			{searchModelStep}
			
			Rating:
			Write a single integer, from -100 to 100, which represents how strongly you think the legislation should be passed, with -100 being it definitely should NOT be passed and 100 being it definitely SHOULD be passed. This rating should directly translate to a grade for the bill, as per the following grading rubric (where r is the rating): A: r >= 60, B: 60 < r >= 40, C: 40 < r >= 20, D: 20 < r >= 0, F: r < 0. If the impact to society is negative, then so too should the rating score - naturally you wouldn't recommend voting for a bill which is bad for society. This rating metric is however separate from the impact scores, in that a bill might be of high rating but it might have a low impact. A common example might be a congressional gold medal award given for a heroic act (high rating), but the overall impact to society is small. Respond with only the integer, no commentary or units.
			
			Bill Title:
			Write the bill title. If the bill does not have a title and is only referred to by its bill number (such as HR 4141), please make up a very short title for the bill based on its content.
			
			Short Report:
			A single paragraph, at least four sentence report which gives a detailed, but not repetitive, summary of the bill, any high level goals, and it's expected impact to society. Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.
			
			Long Report:
			A detailed, but not repetitive, report of the bill which references concrete, notable and specific text of the bill where possible. Make sure to explain: an overall summary of the bill; the high level goals the bill is attempting to achieve, and how it plans to achieve those goals; the impact to society the bill would have, if enacted. Your audience here is general public layman voters, so if you think they won't understand an acronym or a complex topic, please explain it. Should be between one and seven paragraphs long, depending on the complexity of the bill and the topics it covers. If the bill touches on controversial topics such as trans issues or guns rights, please include the advocating logic by proponents and also the advocating logic of the opposition, otherwise do not include this logic. Where relevant, cite scientific studies or the opinions of authoritative knowledge sources to provide more context. Keep in mind that we're trying to figure out how to spend U.S. taxpayer dollars: budgetary concerns are important. Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Laymans Report:
			Your audience is general public layman voters, written at the high-school education or lower level. Do not make this sound like a dry confusing report, try to form an emotional connection and tie it to concrete things they care about. Use analogies and help it to make sense for your average person. You must explain the high level goals of the bill, a high level summary of how it attempts to achieve those goals, and your opinion on the impact the bill will have on society, if enacted. Should be between two and four paragraphs long, depending on the complexity of the bill and the topics it covers. Do not use ancronyms, such as GPO, CBO, etc. If you must use them, they must be defined. Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Confidence:
			A self-rated integer from 0 to 100 measuring how confident you are that your analysis was valid and interpreted correctly.
			
			{searchReferences}
			""";
	
	public static final String statsPrompt;
	public static final String slicePrompt;
	static {
		String issues = String.join("\n", Arrays.stream(TrackedIssue.values()).map(issue -> issue.getName() + ": <score or N/A>").toList());
    	statsPrompt = statsPromptTemplate.replaceFirst("\\{issuesList\\}", issues);
    	slicePrompt = slicePromptTemplate.replaceFirst("\\{issuesList\\}", issues);
	}
	
	public static final String SEARCH_MODEL_INSTRUCTIONS1 = " Some initial web searches may or may not have been done for you and may be included with the bill text in a 'References' section. Feel free to perform your own additional web searches during this process to gain additional context or information such as reasoning from the bill sponsor(s) or budgetary information which may not be apparent from the bill text. If you do find useful information, make sure to reference the source in the long report using markdown link syntax (in addition to placing it in the references at the bottom).";
	public static final String SEARCH_MODEL_INSTRUCTIONS2 = " Where appropriate, please cite references from your search inside the report. References can be cited using markdown link syntax: [explanation text here](http://example.com)";
	public static final String SEARCH_MODEL_STEP = "Step 7: Perform a web search to gather more information and answer any questions you might have.";
	public static final String SEARCH_REFERENCES = "Search References:\nA single line JSON array payload which contains machine readable data about all the references used in your anaylsis. Each reference shall be represented by a JSON array with the following string fields: [\"https://example.org/full/url/here\", \"author\", \"title\", \"sentiment as an integer from -100 to 100\", \"summary\", \"long summary\"]\n"
			+ "The long summary field must contain any and all critical information from the article which you used in your analysis, such that the analysis could be repeated in the future with only this information. Here is an example of a single cited reference (you may have more than one): [[\"https://pmc.ncbi.nlm.nih.gov/articles/PMC9677302/\", \"Sajjad et al., Canadian Journal of Kidney Health and Disease, 2022\", \"Motivators and Barriers to Living Donor Kidney Transplant as Perceived by Past and Potential Donors\", \"20\", \"Peer-reviewed study citing lack of job security as a significant barrier to living donation.\", \"A large proportion of women and men reported that guaranteed job security (47% women and 38% of men), paid time off (51% of women and 42% of men), reimbursement of lost wages (49% of women and 38% of men), and protections to guarantee no impact on future insurability (62% of women and 52% of men) were significant motivators to donate.\"]]";
	
	@Inject
	protected OpenAIService ai;
	
	@Inject
	protected LocalCachedS3Service s3;
	
	@Inject
	protected BillService billService;
	
	public Optional<BillInterpretation> getByBillId(String billId)
	{
		return getByBillId(billId, InterpretationOrigin.POLISCORE);
	}
	
	public Optional<BillInterpretation> getByBillId(String billId, InterpretationOrigin origin)
	{
		return s3.get(BillInterpretation.generateId(billId, origin, null), BillInterpretation.class);
	}
	
	public String getPromptForBill(Bill bill, boolean isAggregate, boolean searchEnabled) {
		String prompt;
		
		if (isAggregate) {
			prompt = aggregatePrompt;
		} else {
			prompt = statsPrompt;
		}
		
		if (searchEnabled) {
			prompt = prompt.replaceFirst("\\{searchModelInstructions1\\}", SEARCH_MODEL_INSTRUCTIONS1);
			prompt = prompt.replaceFirst("\\{searchModelInstructions2\\}", SEARCH_MODEL_INSTRUCTIONS2);
			prompt = prompt.replaceFirst("\\{searchModelInstructions2\\}", SEARCH_MODEL_INSTRUCTIONS2);
			prompt = prompt.replaceFirst("\\{searchModelStep\\}", SEARCH_MODEL_STEP);
			prompt = prompt.replaceFirst("\\{searchReferences\\}", SEARCH_REFERENCES);
		} else {
			prompt = prompt.replaceFirst("\\{searchModelInstructions1\\}", "");
			prompt = prompt.replaceFirst("\\{searchModelInstructions2\\}", "");
			prompt = prompt.replaceFirst("\\{searchModelInstructions2\\}", "");
			prompt = prompt.replaceFirst("\\{searchModelStep\\}", "");
			prompt = prompt.replaceFirst("\\{searchReferences\\}", "");
		}
		
		return prompt;
	}
	
	public String getUserMsgForBill(Bill bill, String billText, OpenAIModel model) {
//		var userMsg = "Bill Text:\n" + billText;
//		
//		val op = s3.get(CBOBillAnalysis.generateId(bill.getId()), CBOBillAnalysis.class);
//		
//		if (op.isPresent()) {
//			userMsg = "Congressional Budget Office Analysis:\n" + op.get().getSummary() + "\n\n" + userMsg;
//		}
//		
//		return userMsg;
		
		String userMsg = "";
		final String billTextMsg = "Official Bill Text:\n" + billText;
		
		var pressInterps = billService.getPressInterps(bill.getId());
		
		if (pressInterps.size() > 0) {
			String header = "References:\n" + "The following articles were pulled from a web search for this bill and were included to provide additional context for the interpretation. Their inclusion does not represent an endorsement of the opinions expressed from the source. Often a web search for a bill will reveal key legislative stakeholders, so view these articles with a skeptical eye. We want to prioritize what's best for all of America, not necessarily a few key stakeholders. Feel free to cite these sources using markdown link syntax in your long report if appropriate and relevant.\n\n";
			
			int context = billTextMsg.length() + header.length();
			
			for (int i = 0; i < pressInterps.size(); ++i)
			{
				var interp = pressInterps.get(i);
				
				String pressText = interp.getAuthor() + "(" + interp.getOrigin().getUrl() + ") - " + interp.getOrigin().getTitle() + ":\n" + interp.getLongExplain() + "\n\n";
				
				context += pressText.length();
				
				if (context < model.getContextWindowStringLength()) {
					if (i == 0) {
						userMsg = header;
					}
					
					userMsg += pressText;
				} else {
					break;
				}
			}
		}
		
		userMsg += billTextMsg;
		
		return userMsg;
	}
	
//	protected BillInterpretation getOrCreateAggregateInterpretation(Bill bill, IssueStats aggregateStats, String aggregateExplain, List<BillInterpretation> sliceInterps)
//	{
//		BillInterpretation bi = new BillInterpretation();
//		bi.setBill(bill);
//		
//		bi.setMetadata(OpenAIService.metadata());
//		bi.setSliceInterpretations(sliceInterps);
//		
//		String aiOut = ai.chat(aggregatePrompt, aggregateExplain);
//		new BillInterpretationParser(bi).parse(aiOut);
//		
//		bi.setIssueStats(aggregateStats);
//		bi.setId(BillInterpretation.generateId(bill.getId(), null));
//		
//		archive(bi);
//		
//		return bi;
//	}
	
//	public BillInterpretation getOrCreate(String billId)
//	{
//		val bill = billService.getById(billId).orElseThrow();
//		val interpId = BillInterpretation.generateId(bill.getId(), null);
//		val cached = s3.get(interpId, BillInterpretation.class);
//		
//		if (cached.isPresent())
//		{
//			return cached.get();
//		}
//		else
//		{
//			val interp = interpret(bill);
//			
//			return interp;
//		}
//	}
	
//	protected BillInterpretation interpret(Bill bill) throws MissingBillTextException
//	{
//		Log.info("Interpreting bill " + bill.getId() + " " + bill.getName());
//		
//		val billText = billService.getBillText(bill).orElseThrow(() -> new MissingBillTextException());
//		
//		bill.setText(billText);
//		
//		if (billText.getXml().length() >= OpenAIService.MAX_SECTION_LENGTH)
//    	{
//    		List<BillSlice> slices = new XMLBillSlicer().slice(bill, bill.getText(), OpenAIService.MAX_SECTION_LENGTH);
//    		List<AISliceInterpretationMetadata> sliceMetadata = new ArrayList<AISliceInterpretationMetadata>();
//    		List<BillInterpretation> sliceInterps = new ArrayList<BillInterpretation>();
//    		
//    		if (slices.size() == 0) throw new UnsupportedOperationException("Slicer returned zero slices?");
//    		else if (slices.size() == 1) {
//    			bill.getText().setXml(slices.get(0).getText()); // TODO : Hackity hack. This achieves our goal of treating it as the bill text but it's not actually xml
//    		} else {
//    			DoubleIssueStats billStats = new DoubleIssueStats();
//    			List<String> aggregateExplain = new ArrayList<String>();
//        		
//        		for (int i = 0; i < slices.size(); ++i)
//        		{
//        			BillSlice slice = slices.get(i);
//        			
//        			BillInterpretation sliceInterp = getOrCreateInterpretation(bill, slice);
//        			
//        			billStats = billStats.sum(sliceInterp.getIssueStats().toDoubleIssueStats());
//        			sliceMetadata.add((AISliceInterpretationMetadata) sliceInterp.getMetadata());
//        			
//        			sliceInterps.add(sliceInterp);
//        			aggregateExplain.add(sliceInterp.getShortExplain());
//        		}
//        		
//        		billStats = billStats.divideByTotalSummed();
//        		
//        		var bi = getOrCreateAggregateInterpretation(bill, billStats.toIssueStats(), String.join("\n", aggregateExplain), sliceInterps);
//        		
//        		return bi;
//    		}
//    	}
//		
//		var bi = getOrCreateInterpretation(bill, null);
//		
//    	return bi;
//	}
	
//	protected BillInterpretation getOrCreateInterpretation(Bill bill, BillSlice slice)
//	{
//		val id = BillInterpretation.generateId(bill.getId(), slice == null ? null : slice.getSliceIndex());
//		val cached = s3.get(id, BillInterpretation.class);
//		
//		if (cached.isPresent())
//		{
//			return cached.get();
//		}
//		else
//		{
//			BillInterpretation bi = new BillInterpretation();
//			bi.setBill(bill);
//			
//			String interpText;
//			if (slice == null)
//			{
//				interpText = ai.chat(slicePrompt, bill.getText().getXml());
//				bi.setMetadata(OpenAIService.metadata());
//			}
//			else
//			{
//				interpText = ai.chat(aggregatePrompt, slice.getText());
//				bi.setMetadata(OpenAIService.metadata(slice));
//			}
//			
//			new BillInterpretationParser(bi).parse(interpText);
//			bi.setId(id);
//			
//			archive(bi);
//			
//			return bi;
//		}
//	}
	
    protected void archive(BillInterpretation interp)
    {
    	s3.put(interp);
    }

	public boolean isInterpreted(@NonNull String billId) {
		val id = BillInterpretation.generateId(billId, InterpretationOrigin.POLISCORE, null);
		val exists = s3.exists(id, BillInterpretation.class);
		
		return exists;
		
//		if (!exists) return false;
//		
//		val aExists = s3.exists(CBOBillAnalysis.generateId(billId), CBOBillAnalysis.class);
//		
//		return !aExists || (aExists && s3.get(id, BillInterpretation.class).get().getBudgetChange10Yr() != null);
	}
	
	public boolean isInterpreted(@NonNull String billId, int sliceIndex) {
		val id = BillInterpretation.generateId(billId, InterpretationOrigin.POLISCORE, sliceIndex);
		return s3.exists(id, BillInterpretation.class);
	}
}
