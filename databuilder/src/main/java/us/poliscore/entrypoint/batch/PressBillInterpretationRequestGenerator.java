package us.poliscore.entrypoint.batch;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Node;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.PoliscoreUtil;
import us.poliscore.ai.BatchOpenAIRequest;
import us.poliscore.ai.BatchOpenAIRequest.BatchBillMessage;
import us.poliscore.ai.BatchOpenAIRequest.BatchOpenAIBody;
import us.poliscore.ai.BatchOpenAIRequest.CustomOriginData;
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.press.BillArticleRecognizer;
import us.poliscore.press.GoogleSearchResponse;
import us.poliscore.press.RedditFetcher;
import us.poliscore.service.BillService;
import us.poliscore.service.LegislatorInterpretationService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.PressInterpService;
import us.poliscore.service.RollCallService;
import us.poliscore.service.SecretService;
import us.poliscore.service.storage.DynamoDbPersistenceService;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.LocalFilePersistenceService;
import us.poliscore.service.storage.MemoryObjectService;

@QuarkusMain(name="PressScraperEntrypoint")
public class PressBillInterpretationRequestGenerator implements QuarkusApplication {
	
	// Google requires us to define this within their console, and it includes some configuration options such as how much of the web we want to search.
	public static final String GOOGLE_CUSTOM_SEARCH_ENGINE_ID = "3564aa93769fe4c0f";
	
	// Google's max queries on free tier is 100
	public static final int MAX_QUERIES = 2000;
	
	public static final long TOKEN_BLOCK_SIZE = 30000000;
	
	private static final String PRESS_INTERPRETATION_PROMPT_TEMPLATE = """
			You will be given what is suspected, but not guaranteed, to be a press article which contains information about the following United States bill currently in congress.
			{{billIdentifier}}

			In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Short Report:') in your response. Do not include the section instructions in your response. Do not include any special formatting or emojis in your response (raw text only please). If the answer to a chain of thought section is ‘no’, you are to write 'NO_INTERPRETATION’ at the end of the chain of thought section and exit without filling out the rest of the sections.
			
			Chain of Thought - Bill Identity Match:
			Were you able to determine with a reasonable certainty that the provided article is actually about the bill in question? Some evidence to consider
			1. Was the name of the bill included somewhere in the article?
			2. Was the article published after the introduction date of the bill?
			3. Was the bill number mentioned anywhere in the article?
			4. Does the bill ‘namespace’ appear to match - i.e. if the bill is in Congress, the article should be written about a federal bill, not a state bill, and vise versa. If the bill is a state bill, the article should be written about a state legislature.
			5. Does the general content of the article match the bill’s purpose
			
			All five of these criteria are NOT required for a bill identity match to be positive, use your best judgement. If a bill identity match was not found, you are to end this section with ‘NO_INTERPRETATION’ and exit.
			
			Chain of Thought - Identify Useful Information:
			In this section you are to determine if the article actually includes any useful or notable information, beyond what can be easily scraped from congress or the bill text. Some examples of useful information are as follows
			1. Text that states whether a particular organization supports (or does not support), endorses, or has otherwise influenced or lobbied the bill
			2. A conversation thread where people are expressing opinions, either positive or negative, or providing insight into the bill
			3. Any sort of analysis which may give insight into the bill’s predicted impact to society or a subset of society
			4. A pundit analysis of the bill
			5. An organization saying that they are either scared or excited or some internal feeling they might have about the bill
			
			The following does NOT count as useful information
			1. General ‘legislative updates’ where an organization is describing that a bill has been introduced, without giving any opinion either for or against the bill or offering any additional useful information beyond what can easily be scraped from congress
			2. A ‘bill tracker’ page, outlining what the bill is, maybe giving an AI summary, and basic information about the bill
			
			‘Legislative update’ articles are tricky! Sometimes they include useful information, sometimes they do not. Use your best judgement. If useful information was not found, you are to end this section with ‘NO_INTERPRETATION’ and exit.
			
			Author:
			Write the name of the organization and/or author responsible for drafting the analysis, or N/A if it is not clear from the text. If the text has both an author and an organization, write the organization followed by the first / last name of the author (e.g. 'New York Times - Joe Schmoe'). If the text comes from social media and there is a singular author, you may write the name of the user and the website it was written (e.g. 'Reddit - <username>'). If the text was taken from social media and there is more than one author, you shall only place the name of the social media website (e.g. 'Twitter'). In the case of Reddit, please include the subreddit (e.g. 'Reddit (r/politics). 
						
			Title:
			Provide a "cleaned up" title from the title you were provided. This title should remove duplicative or unnecessary information, such as the author (as that will be listed separately), and it should aim to be concise without removing essential information.
						
			Sentiment:
			Provide a number from -100 to 100 which summarizes the article's opinion of the bill. If the article recommends voting against the bill or provides harsh criticisms, these scores should be negative, otherwise if it recommends voting for the bill they should be positive.
						
			Short Report:
			A one sentence summary of the text. Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.
						
			Long Report:
			A detailed, but not repetitive summary of the analysis which references concrete, notable and specific text of the analysis where possible. This report be no longer than three paragraphs and should explain the author's opinion or stance on the bill, any high level goals, and it's predictions of the bill's expected impact to society (if any). Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.
						
			Confidence:
			A self-rated number from 0 to 100 measuring how confident you are that your analysis was valid and interpreted correctly.
		""";
	
	/* This is an older prompt (run on gpt-4o) which many press interpretations were generated. It worked pretty well, but due to cost reasons we want to get off gpt-4o 

	 		You will be given what is suspected, but not guaranteed, to be a press article which contains information about the following United States bill currently in congress.
			{{billIdentifier}}
			
			The first thing you must determine is if this text offers any interesting or useful analysis, information or an organization's stance about the bill in question. We are looking for information beyond what can be easily scraped from congress, so information such as basic voting information, an introduction date, a bill title, or simply an announcement that a bill was introduced or has passed does NOT count.
			
			If the provided text is NOT an interpretation of this bill or if the interpretation is of a different bill, you are to immediately respond as 'NO_INTERPRETATION - <reason>' where <reason> is the reason why you don't think it's a valid interpretation for this bill and EXIT.
			
			*IMPORTANT* If the text states that a particular organization either supports or does not support the bill in question, DO NOT return 'NO_INTERPRETATION'. The analysis does NOT have to be a detailed one, a simply stated opinion from a reputable organization is enough.
			
			Generally speaking, the workflow is as follows:
			1. Is the article actually about this bill? If not, respond 'NO_INTERPRETATION - <reason>'
			2. Does the article say anything useful? An easy yes here would be: informative conversation threads containing analysis, a pundit analysis, a press release containing an endorsement or condemntation, or an article by a major news organization about a bill. If the article does not contain useful information, respond as 'NO_INTERPRETATION - <reason>'.
			3. Otherwise, fill out the response template as specified below.
			
			IF you determine this is a valid interpretation of the bill, then your instructions are as follows:
			
			You are part of a non-partisan oversight committee, tasked to read and summarize the provided analysis, focusing especially on any predictions the analysis might make towards the bill's impact to society, as well as any explanations or high-level logic as to how or why. If possible, please include information about the author as well as any organization they may be a part of. In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Summary:') in your response. Do not include the section instructions in your response. Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.
			
			If you are given a social media conversation (such as Reddit) you should keep in mind that the conversation may represent a lens through which to view a sizeable chunk of the population's relationship with a particular bill. In this scenario, you should consider comments which confidently state concrete opinions (and exclude exploratory comments) to generate an interpretation of the bill. Do not make any mention in your summary of comments being "highly upvoted". In your summary, provide a summary of the "public discussion" found in the thread.
			
			=== BEGIN response template ===
			
			Author:
			Write the name of the organization and/or author responsible for drafting the analysis, or N/A if it is not clear from the text. If the text has both an author and an organization, write the organization followed by the first / last name of the author (e.g. 'New York Times - Joe Schmoe'). If the text comes from social media and there is a singular author, you may write the name of the user and the website it was written (e.g. 'Reddit - <username>'). If the text was taken from social media and there is more than one author, you shall only place the name of the social media website (e.g. 'Twitter'). In the case of Reddit, please include the subreddit (e.g. 'Reddit (r/politics). 
			
			Title:
			Provide a "cleaned up" title from the title you were provided. This title should remove duplicative or unnecessary information, such as the author (as that will be listed separately), and it should aim to be concise without removing essential information.
			
			Sentiment:
			Provide a number from -100 to 100 which summarizes the article's opinion of the bill. If the article recommends voting against the bill, these scores should be negative, otherwise if it recommends voting for the bill they should be positive.
			
			Short Report:
			A one sentence summary of the text. Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.
			
			Long Report:
			A detailed, but not repetitive summary of the analysis which references concrete, notable and specific text of the analysis where possible. This report be no longer than three paragraphs and should explain the author's opinion or stance on the bill, any high level goals, and it's predictions of the bill's expected impact to society (if any). Do not include any formatting text, such as stars or dashes. Do not include non-human readable text such as XML ids.
			
			Confidence:
			A self-rated number from 0 to 100 measuring how confident you are that your analysis was valid and interpreted correctly.
			
			=== END response template ===
			
			Multishot prompt examples:
			
			==USER==
			title: Congress Extends Medicare Telehealth Authority Through September
			url: https://www.afw.com/telehealth/news/hr1968
			
			Congress recently passed H.R. 1968, extending Medicare telehealth authority for audiologists and speech-language pathologists (SLPs) through September 30, 2025. AFW is actively working to secure permanent telehealth status by supporting the reintroduction of the Expanded Telehealth Access Act, which would permanently authorize audiologists, SLPs, physical therapists, and occupational therapists as Medicare telehealth providers. Advocates are encouraged to contact their representatives to push for this change. This extension affects Medicare only and does not impact Medicaid telehealth coverage, which remains state-specific.
			
			==AGENT==
			NO_INTERPRETATION - The article mentions that H.R. 1968 was passed, but it does not provide a concrete opinion either for or against the bill. Instead, it offers an opinion about a different bill. This is more of a news update than it is a bill interpretation.
			
			==USER==
			title: MEALS ON WHEELS AMERICA ISSUES STATEMENT ON PASSAGE OF H.R. 1968, FULL-YEAR CONTINUING APPROPRIATIONS AND EXTENSIONS ACT, 2025
			url: https://www.mow.com/learn-more/national/press-room/news/2025/hr1968
			
			ARLINGTON, Va., March 14, 2025 – Meals on Wheels America President and CEO Ellie Hollander today issued this statement in response to the passage of H.R. 1968, Full-Year Continuing Appropriations and Extensions Act, 2025 on March 14, 2025: 
			Meals on Wheels America is deeply disappointed that Congress has failed yet again to prioritize the needs of our nation’s older adults by passing a long-term continuing resolution (CR) instead of comprehensive spending bills that could have made critical investments to address the growing crises of senior hunger and isolation.  
			This CR extends government funding through September 30, 2025. A government shutdown is never the desired outcome, but the flat funding levels set in this CR represent, in effect, a funding cut that could lead to a reduction in services, given rising costs and increased need among a rapidly growing senior population. This represents a missed opportunity to prioritize seniors and will further strain the nationwide network of Meals on Wheels providers.  
			Even though Meals on Wheels serves more than 2 million seniors, we know that an additional 2.5 million likely need meals but are not receiving essential services, and 1 in 3 providers already has a waitlist. America’s seniors cannot wait any longer for lifesaving nutritious meals and moments of connection.  
			We remain committed to working with Congress and the administration to advocate for much-needed funding increases and stability for senior nutrition providers that have been operating under uncertain conditions for far too long. Meals on Wheels America urgently calls on Congress to increase funding for senior nutrition programs. 
			
			==AGENT==
			Author:
			Meals on Wheels
			
			Sentiment:
			-30
			
			Title:
			Meals on Wheels America Issues Statement on Passage of H.R. 1968
			
			Short Report:
			Meals on Wheels issues a press release stating that they are "deeply disappointed  that Congress has failed yet again to prioritize the needs of our nation’s older adults".
			
			Long Report:
			Meals on Wheels America, led by President and CEO Ellie Hollander, expresses strong disappointment with the passage of H.R. 1968, the Full-Year Continuing Appropriations and Extensions Act of 2025. The organization criticizes Congress for passing another continuing resolution (CR) instead of comprehensive spending bills that could have "made critical investments to address the growing crises of senior hunger and isolation." The main goal highlighted by the statement is securing greater, stable funding for senior nutrition programs, which Meals on Wheels argues are already under significant strain. Hollander warns that the flat funding levels in the CR, despite technically avoiding a government shutdown, effectively amount to a cut because of rising operational costs and an expanding senior population, predicting that this will lead to service reductions nationwide. The analysis emphasizes that over 2 million seniors are currently served by Meals on Wheels, yet an estimated additional 2.5 million seniors remain in need, with one in three providers maintaining waitlists. The organization asserts that "America’s seniors cannot wait any longer" and urges Congress to immediately prioritize increased funding. The broader societal impact predicted is worsening hunger, isolation, and health risks among older adults, unless substantial investments are made soon to stabilize and expand services.
			
			==USER==
			title: Another Win this Week for Common Sense – President Trump Signs CRA Resolution Nullifying Methane Tax Regulations on Energy Producers
			url: https://www.ipaa.org/another-win-this-week-for-common-sense

			IPAA Appreciates Quick Action by President on Hoeven and Pfluger Legislation 
			WASHINGTON – Independent Petroleum Association of America (IPAA) President & CEO Jeff Eshelman issued the following statement today on President Donald Trump signing H.J.Res.35 which through the Congressional Review Act process disapproves of the Biden Environmental Protection Agency’s (EPA) methane tax regulations:
			“In another win this week for common sense, President Trump and his Administration have taken action to nullify the regulations the Biden Administration established to implement the misguided methane tax on oil and natural gas producers. On Wednesday, EPA announced that the agency is reconsidering its Subpart OOOOb and Subpart OOOOc regulations and its Subpart W greenhouse gas reporting program rules – reconsideration provides a pathway for making these regulations more cost-effective and well-structured. IPAA appreciates President Trump moving quickly to sign this Congressional Review Act resolution and the initiative of Senator John Hoeven (R-ND) and Congressman August Pfluger (R-TX) in guiding the resolution through Congress.
			“IPAA and our members remain committed to working with the EPA to find a regulatory pathway designed for the sources it regulates, while encouraging continued progress toward reducing emissions. Big new oil and natural gas wells and low producing older wells have differing emissions profiles. Our members are making constant improvements to the technology being used to reduce, measure and report on emissions.”
			
			==AGENT==
			Author:
			IPAA (Independent Petroleum Association of America)
			
			Sentiment:
			100
			
			Title:
			Another Win this Week for Common Sense – President Trump Signs CRA Resolution Nullifying Methane Tax Regulations on Energy Producers
			
			Short Report:
			... excluded for mutlishot brevity ...
			
			Long Report:
			... excluded for mutlishot brevity ...
	 */
	
	
	public static final String PRESS_INTERPRETATION_PROMPT;
	static {
		String issues = String.join("\n", Arrays.stream(TrackedIssue.values()).map(issue -> issue.getName() + ": <score or N/A>").toList());
		PRESS_INTERPRETATION_PROMPT = PRESS_INTERPRETATION_PROMPT_TEMPLATE.replaceFirst("\\{issuesList\\}", issues);
	}
	
	public static String AI_MODEL = "gpt-4.1-mini";
	
	@Inject
	private MemoryObjectService memService;
	
	@Inject
	private LocalFilePersistenceService localStore;
	
	@Inject
	private DynamoDbPersistenceService ddb;
	
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	private BillService billService;
	
	@Inject
	private LegislatorService legService;
	
	@Inject
	private RollCallService rollCallService;
	
	@Inject
	private LegislatorInterpretationService legInterp;
	
	@Inject
	private SecretService secretService;
	
	@Inject PressInterpService pressService;
	
	private long tokenLen = 0;
	
	private long totalRequests = 0;
	
	private int totalQueries = 0;
	
	private List<BatchOpenAIRequest> requests = new ArrayList<BatchOpenAIRequest>();
	
	private List<File> writtenFiles = new ArrayList<File>();
	
	private Set<Bill> dirtyBills = new HashSet<Bill>();
	
	private Set<Bill> queriedBills = new HashSet<Bill>();
	
	// If you need to reprocess a particular bill for whatever reason, add it here. When we run, we will always process any bills in this list, so be careful
	// not to commit this list with anything in it.
	public static final String[] specificFetch = new String[] {
//			Bill.generateId(CongressionalSession.S119.getNumber(), BillType.HR, 1)
	};
	
	public static final boolean FORCE_REINTERPRET = specificFetch.length > 0;
	
	public static AIInterpretationMetadata metadata()
	{
		return AIInterpretationMetadata.construct(OpenAIService.PROVIDER, AI_MODEL, 0);
	}
	
	@SneakyThrows
	public List<File> process()
	{
		Log.info("Scraping press articles");
		
		legService.importLegislators();
		billService.importBills();
		rollCallService.importUscVotes();
		
		s3.optimizeExists(BillText.class);
		s3.optimizeExists(PressInterpretation.class);
		s3.optimizeExists(BillInterpretation.class);
		
		int block = 1;
		tokenLen = 0;
		totalRequests = 0;
		requests = new ArrayList<BatchOpenAIRequest>();
		writtenFiles = new ArrayList<File>();
		
		// Determine what bills to process //
		Stream<Bill> bills;
		if (specificFetch.length > 0) {
			bills = memService.query(Bill.class).stream().filter(b -> Arrays.asList(specificFetch).contains(b.getId()));
		} else {
			bills = memService.query(Bill.class).stream().filter(b ->
				b.isIntroducedInSession(PoliscoreUtil.CURRENT_SESSION)
				&& s3.exists(BillText.generateId(b.getId()), BillText.class));
//				&& b.getIntroducedDate().isBefore(LocalDate.now().minus(10, ChronoUnit.DAYS)) // Must be at least x days old (otherwise there won't be press coverage) - Commented out. If we're going to pass the bill text through AI we might as well scan for press. Ideally this filter criteria would exactly match the bill request generator
		}
		
		int remainingBillsCount = 0;
		var quotaLimitHit = false;
		
		// Process the bills //
		for (Bill b : bills.sorted(Comparator.comparing(Bill::getDate).reversed()).collect(Collectors.toList())) {
			// Don't interpret really old bills
			if (!Arrays.asList(specificFetch).contains(b.getId()) && b.getDate().isBefore(LocalDate.now().minus(101, ChronoUnit.DAYS))) {
//				val interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class);
//				if (interp.isPresent() && interp.get().getLastPressQuery() != LocalDate.EPOCH) continue;
				
				continue; // we won't always need to check the interp's lastPressQuery so long as we keep on top of generation
			}
			
			if (!quotaLimitHit && totalQueries < MAX_QUERIES) {
				if (FORCE_REINTERPRET)
					deleteExisting(b);
				
				try {
					processBill(b);
				} catch (GoogleQuotaExceededException ex) {
					Log.info("Hit google's search quota limit. Haulting further queries and returning.");
					quotaLimitHit = true;
					continue;
				}
			} else {
				remainingBillsCount++;
			}
			
			if (tokenLen >= TOKEN_BLOCK_SIZE) {
				writeBlock(block++);
			}
		}
//		processOrigin(b, new InterpretationOrigin("url", "title"), Jsoup.parse(new File("/Users/rrowlands/dev/projects/poliscore/databuilder/src/main/resources/ace-ccr.html")));
//		processOrigin(b, new InterpretationOrigin("https://www.reddit.com/r/NeutralPolitics/comments/1jawsml/what_are_the_pros_and_cons_of_voting_for_hr1968", "What are the PROS and CONS of voting for H.R.1968 - Full-Year Continuing Appropriations and Extensions Act, 2025?"));
//		processOrigin(b, new InterpretationOrigin("https://www.asha.org/news/2025/congress-extends-medicare-telehealth-authority-through-september/", "Congress Extends Medicare Telehealth Authority Through September"));
//		processOrigin(b, new InterpretationOrigin("https://www.aamc.org/news/press-releases/aamc-statement-passage-full-year-continuing-resolution", "Medicare Telehealth Flexibilities Extended, but Without Promise of Permanent Solution"));
		
		writeBlock(block++);
		
		Log.info(remainingBillsCount + " bills remaining to process");
		
		Log.info("Press scraper complete. Executed " + totalQueries + " Google search queries and generated " + totalRequests + " AI requests.");
		
		return writtenFiles;
	}
	
	public void deleteExisting(Bill b)
	{
		var pressInterps = billService.getAllPressInterps(b.getId());
		
		for (val interp : pressInterps)
		{
//			if (interp.getId().contains("reddit"))
				s3.delete(interp.getId(), PressInterpretation.class);
		}
		
		Log.info("Deleted " + pressInterps.size() + " existing interpretations");
	}
	
	
	/**
	 * @return Only bills for which an actual request has been generated
	 */
	public Set<Bill> getDirtyBills()
	{
		return dirtyBills;
	}
	
	/**
	 * 
	 * @return All bills which were queried during this run
	 */
	public Set<Bill> getQueriedBills() {
		return queriedBills;
	}
	
	@SneakyThrows
	private void processBill(Bill b) {
		var interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).orElse(null);
		
		if (interp != null && LocalDate.now().isAfter(b.getIntroducedDate().plus(19, ChronoUnit.DAYS)) && interp.getLastPressQuery().isAfter(LocalDate.now().minus(30, ChronoUnit.DAYS)) && !Arrays.asList(specificFetch).contains(b.getId())) return; // Skip if it was interpreted in the last x days
		if (interp != null && LocalDate.now().isBefore(b.getIntroducedDate().plus(19, ChronoUnit.DAYS)) && interp.getLastPressQuery().isAfter(LocalDate.now().minus(10, ChronoUnit.DAYS)) && !Arrays.asList(specificFetch).contains(b.getId())) return;
		if (interp == null) interp = new BillInterpretation();
		
		final String typeAndNumber = b.getType().getName().toUpperCase() + " " + b.getNumber();
		
		String query;
		if (b.getName() == null || StringUtils.isEmpty(b.getName()) || b.getName().toLowerCase().replaceAll("[\\s\\.]+", "").equals(typeAndNumber.toLowerCase().replaceAll("[\\s\\.]+", "")))
		{
			query = typeAndNumber;
			
			if (b.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
				query = "Congress " + query;
		}
		else
		{
			query = b.getType().getName().toUpperCase() + " " + b.getNumber() + " " + b.getName();
		}
		
	    val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
	    
	    boolean generatedRequest = false;

	    generatedRequest = fetchAndProcessSearchResults(b, encodedQuery, 1) || generatedRequest;
	    
	    // Fetch an extra page for laws
	    if (b.getStatus().getProgress() == 1.0f)
	    	generatedRequest = fetchAndProcessSearchResults(b, encodedQuery, 11) || generatedRequest;
	    
	    if (generatedRequest)
	    	dirtyBills.add(b);
	    
	    queriedBills.add(b);
	}
	
	public void recordLastPressQueries()
	{
		Log.info("Updating LastPressQuery for interpreted bills");
		
		for (var b : queriedBills) {
			val interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).orElse(null);
			
			if (interp != null) {
				interp.setLastPressQuery(LocalDate.now());
			    s3.put(interp);
			}
		}
	}

	@SneakyThrows
	private boolean fetchAndProcessSearchResults(Bill b, String encodedQuery, int startIndex) {
		if (totalQueries >= MAX_QUERIES) return false;
		
		Log.info("Performing google search for press articles [" + encodedQuery + "]");
		
	    final String url = "https://customsearch.googleapis.com/customsearch/v1?key=" + 
	                        secretService.getGoogleSearchSecret() + 
	                        "&cx=" + GOOGLE_CUSTOM_SEARCH_ENGINE_ID + 
	                        "&q=" + encodedQuery + 
	                        "&start=" + startIndex;

	    String sResp = fetchUrl(url);
	    val resp = new ObjectMapper().readValue(sResp, GoogleSearchResponse.class);

	    if (resp.getItems() == null) return false;
	    
	    boolean generatedRequest = false;

	    for (val item : resp.getItems())
		{
	    	try
	    	{
				if (!item.getLink().endsWith(".pdf") && StringUtils.isBlank(item.getFileFormat()))
				{
					val origin = new InterpretationOrigin(item.getLink(), item.getTitle());
					
					generatedRequest = processOrigin(b, origin) || generatedRequest;
				}
	        } catch (Exception e) {
	            Log.warn("General error connecting to " + item.getLink() + ": " + e.getMessage());
	        }
		}
	    
	    totalQueries++;
	    
	    return generatedRequest;
	}
	
	private boolean processOrigin(Bill b, InterpretationOrigin origin)
	{
		if (!FORCE_REINTERPRET && s3.exists(PressInterpretation.generateId(b.getId(), origin), PressInterpretation.class)) return false;
		
		String articleText = pressService.fetchArticleText(origin);
		
		if (articleText != null)
		{
			return processArticle(b, origin, articleText);
		}
		
		return false;
	}
	
	protected boolean processArticle(Bill b, InterpretationOrigin origin, String articleText)
	{
		float confidence = BillArticleRecognizer.recognize(b, articleText, origin.getUrl());
		
//		Log.info("Confidence that " + origin.getUrl() + " is written about bill " + b.getId() + " resolved to " + confidence);
		
		if (confidence > 0.4f)
		{
			return interpretArticle(b, origin, articleText);
		}
		
		return false;
	}
	
	private boolean interpretArticle(Bill b, InterpretationOrigin origin, String body)
	{
		String oid = PressInterpretation.generateId(b.getId(), origin);
		var data = new CustomOriginData(origin, oid);
		
		var prompt = PRESS_INTERPRETATION_PROMPT.replace("{{billIdentifier}}", "United States, " + b.getSession() + "th Congress" + ", " + b.getOriginatingChamber().getName() + "\n" + b.getType().getName() + " " + b.getNumber() + " - " + b.getName() + "\nIntroduced in " + b.getIntroducedDate());
		
		String text = "title: " + origin.getTitle() + "\nurl: " + origin.getUrl() + "\n\n";
		
		int maxLen = OpenAIService.MAX_GPT4o_REQUEST_LENGTH - prompt.length();
				
		text += body;
		if (text.length() > maxLen)
			text = text.substring(0, maxLen);
		
		createRequest(data, prompt, text);
		
		return true;
	}
	
	private void createRequest(CustomOriginData data, String sysMsg, String userMsg) {
		if ((userMsg.length() + sysMsg.length()) > OpenAIService.MAX_GPT4o_REQUEST_LENGTH) {
			throw new RuntimeException("Max user message length exceeded on " + data.getOid() + " (" + userMsg.length() + " > " + OpenAIService.MAX_GPT4o_REQUEST_LENGTH);
		}
		
		List<BatchBillMessage> messages = new ArrayList<BatchBillMessage>();
		messages.add(new BatchBillMessage("system", sysMsg));
		messages.add(new BatchBillMessage("user", userMsg));
		
		requests.add(new BatchOpenAIRequest(
				data,
				new BatchOpenAIBody(messages, AI_MODEL)
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
		return new File(Environment.getDeployedPath(), "openapi-press-bulk-" + blockNum + ".jsonl");
	}
	
	@SneakyThrows
	private String fetchUrl(String url) {
	    final int maxRetries = 5;
	    final long baseDelayMillis = 1000; // 1 second base
	    int attempt = 0;

	    while (true) {
	        try {
	            HttpResponse<String> response = HttpClient.newHttpClient()
	                .send(HttpRequest.newBuilder().uri(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());

	            int status = response.statusCode();
	            String body = response.body();

	            if (status == 429 || body.contains("\"reason\":\"rateLimitExceeded\"")) {
	                throw new GoogleQuotaExceededException("Google Search API quota exceeded: " + body);
	            }

	            if (status >= 500 && status < 600) {
	                throw new IOException("Transient Google API error " + status + ": " + body);
	            }

	            if (status != 200) {
	                Log.error("Google API request failed: " + body);
	                throw new RuntimeException("Google API error " + status + ": " + body);
	            }

	            return body;
	        } catch (GoogleQuotaExceededException e) {
	            throw e; // don't retry, break immediately
	        } catch (IOException e) {
	            if (++attempt > maxRetries) {
	                Log.error("Max retries reached for Google API call. Giving up.");
	                throw e;
	            }
	            long delay = baseDelayMillis * (1L << (attempt - 1)); // exponential backoff
	            Log.warn("Google API call failed (attempt " + attempt + "): " + e.getMessage() + ". Retrying in " + delay + "ms...");
	            Thread.sleep(delay);
	        }
	    }
	}
	
	public class GoogleQuotaExceededException extends RuntimeException {
	    private static final long serialVersionUID = -1070397745717473884L;
		public GoogleQuotaExceededException(String message) {
	        super(message);
	    }
	}
	
	@Override
    public int run(String... args) throws Exception {
        process();
        
        Quarkus.waitForExit();
        return 0;
    }
	
	public static void main(String[] args) {
		Quarkus.run(PressBillInterpretationRequestGenerator.class, args);
		Quarkus.asyncExit(0);
	}
}