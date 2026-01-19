package us.poliscore.service;

import java.util.Arrays;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.val;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.entrypoint.DatabaseBuilder;
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
			You will be given the text of a United States bill. Your role is to be a non-partisan oversight committee, performing an impact analysis which evaluates whether or not the following bill is predicted to produce a positive overall benefit to society. In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Impact:') in your response. Do not include the section instructions in your response. Do not ever use 'I' language (as in, I reached this conclusion because...).
			
			{searchModelInstructions1}
			
			Neutral Summary:
			Read the bill text in its entirety and write a strictly neutral, non-partisan summary. This section must contain only officially verifiable information drawn from the bill text itself or widely accepted, non-partisan reference sources. The summary should be between one and three paragraphs, depending on the complexity of the bill. Start by describing what the bill does and the mechanisms it uses to achieve its stated objectives, including relevant thresholds, authorities, timelines, or structural changes where necessary. Avoid evaluative or predictive language. If relevant, briefly describe the stated rationale of supporters and the stated concerns of opponents, presenting both perspectives factually and symmetrically, without implying which is stronger or more correct. Your tone must remain strictly neutral and descriptive. Do not take sides, imply outcomes, assess impacts, or suggest whether the policy is good or bad. This section should be analogous in style and purpose to Congress.gov’s “Official Summary” and should be suitable for citation by journalists as reference-grade material. You may include links to neutral, non-partisan sources (for example, Congress.gov or Wikipedia) using markdown link syntax where appropriate. Do not include formatting symbols such as stars or dashes (excluding links). Do not include non-human-readable text such as XML identifiers, internal IDs, or metadata.

			Bill Title:
			Write the bill title. If the bill does not have a title and is only referred to by its bill number (such as HR 4141), please make up a very concise title for the bill based on its content. If the bill has a title, but it is confusing, vague, too long, or would otherwise be poorly understood by the general public, please make up a very concise title for the bill based on its content.

			Structural Analysis:
			Your goal in this section is to evaluate the bill across seven core pillars. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the pillar number, name and a colon, exactly as written here, followed by your analysis. Conclude each pillar analysis by writing either exactly "<PASS>", or "<FAIL>", denoting that the bill has either passed or failed that pillar of the structural analysis.
			
			1. Precision:
			Does the policy accurately diagnose the underlying issue and target the relevant causal mechanisms?
			2. Evidence:
			Is the proposed intervention supported by empirical research, historical precedent, or meaningful comparative data?
			3. Feasibility:
			Can existing institutions realistically execute the policy given resource, logistical, administrative, and temporal constraints?
			4. Budget:
			Does the policy use resources responsibly, minimize waste, and avoid unsustainable long-term obligations?
			5. Fairness:
			How are benefits and burdens distributed across populations, and does the policy unjustifiably disadvantage certain groups?
			6. Governance:
			Does the policy maintain transparency, accountability, and resilience while minimizing opportunities for corruption or abuse?
			7. Risk:
			Does the policy introduce fragility, perverse incentives, or cascading failures that undermine the intended outcomes?
			
			Impact Analysis:
			Based on all previous bill analysis, you will now perform an impact analysis. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the step number, name and a colon, exactly as written here, followed by your analysis. Do not attempt to quantify impact in this section (that will happen in a later section).
			
			1. Baseline:
			Provide 1–3 sentences which defines the baseline for which this bill impact is operating upon. Impact to society must be calculated relative to this baseline. End this section with one of the following machine-readble identifiers
			- CURRENT_LAW (name the law)
			- LIKELY_TRAJECTORY (absent new legislation)
			- NO_ACTION
			- OTHER (specify)
			
			2. Affected Parties:
			Provide 1-3 sentences describing exactly who the affected party(s) will be and how you determined it. End this section with one of the following machine-readble identifiers
			- VERY_NARROW (<0.1% of population)
			- NARROW (0.1%–1%)
			- MODERATE (1%–10%)
			- BROAD (10%–50%)
			- NEAR_UNIVERSAL (>50%)
			
			3. Directionality:
			Select one.
			- NET_BENEFICIAL
			- NET_HARMFUL
			- MIXED
			- UNCLEAR
			
			4. Effect Magnitude (per affected person):
			Provide 1-3 sentences describing the magnitude per affected person, and how you determined it. End this section with one of the following machine-readble identifiers
			- NEGLIGIBLE
			- MINOR
			- MODERATE
			- SIGNIFICANT
			- SEVERE
			
			5. Temporal Horizon:
			Provide 1-3 sentences describing the impact timeline and how you determined it. End this section with one of the following machine-readble identifiers
			- IMMEDIATE (0–5 years)
			- MEDIUM_TERM (5–20 years)
			- LONG_TERM (20+ years)
			
			6. Risk Structure:
			Provide 1-3 sentences describing the risk structure and how you determined it. End this section with one of the following machine-readble identifiers
			- LINEAR_EFFECTS
			- THRESHOLD_DEPENDENT
			- TAIL_RISK
			- SYSTEMIC_RISK
			- IRREVERSIBLE_HARM
			- REVERSIBLE_EFFECTS
			
			7. Reversibility:
			Provide 1-3 sentences describing the reversibility and how you determined it. End this section with one of the following machine-readble identifiers
			- EASILY_REVERSIBLE
			- DIFFICULT_TO_REVERSE
			- LARGELY_IRREVERSIBLE
			
			8. Summary:
			Provide a concise paragraph explaining:
			- the primary causal pathways
			- how impacts differ from the stated baseline
			- key assumptions
			- major uncertainties
			- any known thresholds, tipping points, or cumulative risks
			
			Long Report:
			Your audience is political enthusiasts and/or professionals, written at a college education level. Your first paragraph will explain the high level goals of the bill, give a high level summary of how it attempts to achieve those goals, and then conclude by giving an analysis on the impact the bill will have on society, if enacted. After your high-level summary, you will then go on to explain, in depth, how you came to these conclusions. You may conclude by identifying winners and losers of the bill, and identifying industry stakeholders, if relevant. Should be between three and seven paragraphs long, depending on the complexity of the bill and the topics it covers. If the bill touches on controversial topics such as trans issues or guns rights, please include the advocating logic by proponents and also the advocating logic of the opposition, otherwise do not include this logic. Where relevant, cite scientific studies or the opinions of authoritative knowledge sources to provide more context. If acronyms are referenced, which are not common knowledge, please define them.  Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Confidence:
			A self-rated integer from 0 to 100 measuring how confident you are that your analysis was valid and interpreted correctly.
			
			Impact Stats:
			Score the following bill on the estimated impact to the United States upon the following criteria, rated from -100 (very harmful) to 0 (neutral) to +100 (very helpful) or N/A if it is not relevant. Scores near ±10 indicate marginal or localized effects; ±30 indicate meaningful policy impact; ±60 indicate major national consequences; ±90 indicate rare, transformative effects. Use the full scale sparingly and proportionally. There is an important distinction between 0 and N/A. Use 0 if the goal of the bill was to provide impact in the policy area - but you are predicting that it will have none. Use N/A if the goals of the bill do not align at all with the policy area. Respond with only the integer and an optional sign, no commentary or units.
			
			{issuesList}
			
			Rating:
			Write a single integer, from -100 to 100, which represents how strongly you think the legislation should be passed, with -100 being it definitely should NOT be passed and 100 being it definitely SHOULD be passed. This rating should directly translate to a grade for the bill, as per the following grading rubric (where r is the rating): A: r >= 60, B: 60 > r >= 40, C: 40 > r >= 20, D: 20 > r > 0, F: r <= 0. If the impact to society is negative, then so too should the rating score - naturally you wouldn't recommend voting for a bill which is bad for society. This rating metric is however separate from the impact scores, in that a bill might be of high rating but it might have a low impact. A common example might be a congressional gold medal award given for a heroic act (high rating), but the overall impact to society is small. Respond with only the integer, no commentary or units.

			Casual Report:
			Your audience is the general public, written at the high-school education level. Your primary goal here is to take the insights from the long report and make them available for your average person. Begin by explaining the high level goals of the bill and a high level summary of how it attempts to achieve those goals. Then, explain all the essential logic required to understand your predicted impact to society, including any research, expert opinions, societal wisdom and/or any competing concerns which may exist. Conclude with your findings on the impact the bill will have on society. Should be between one and three paragraphs long, depending on the complexity of the bill and the topics it covers (controversial topics require more evidence to support your conclusions). Do not use acronyms, such as GPO, CBO, etc. If you must use them, they must be defined. You may link to sources using markdown link syntax, where relevant. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Short Report:
			Your audience is the general public, written at the high-school education level. A single paragraph, at least four sentence report which gives a detailed, but not repetitive, summary of the bill, any high level goals, and its expected impact to society. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.
			
			{searchReferences}
			""";
	
	public static final String slicePromptTemplate = """
			You will be given the text of a United States bill. Your role is to be a non-partisan oversight committee, evaluating whether or not the following bill will produce a positive overall benefit to society. In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Impact:') in your response. Do not include the section instructions in your response. Do not ever use 'I' language (as in, I reached this conclusion because...).

			Structural Analysis:
			Your goal in this section is to evaluate the bill across seven core pillars. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the pillar number, name and a colon, exactly as written here, followed by your analysis. Conclude each pillar analysis by writing EXACTLY one of "<PASS>", or "<FAIL>" (not mixed), denoting that the bill has either passed or failed that pillar of the structural analysis.
			
			1. Precision:
			Does the policy accurately diagnose the underlying issue and target the relevant causal mechanisms?
			2. Evidence:
			Is the proposed intervention supported by empirical research, historical precedent, or meaningful comparative data?
			3. Feasibility:
			Can existing institutions realistically execute the policy given resource, logistical, administrative, and temporal constraints?
			4. Budget:
			Does the policy use resources responsibly, minimize waste, and avoid unsustainable long-term obligations?
			5. Fairness:
			How are benefits and burdens distributed across populations, and does the policy unjustifiably disadvantage certain groups?
			6. Governance:
			Does the policy maintain transparency, accountability, and resilience while minimizing opportunities for corruption or abuse?
			7. Risk:
			Does the policy introduce fragility, perverse incentives, or cascading failures that undermine the intended outcomes?
			
			Impact Analysis:
			Based on all previous bill analysis, you will now perform an impact analysis. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the step number, name and a colon, exactly as written here, followed by your analysis. Do not attempt to quantify impact in this section (that will happen in a later section).
			
			1. Baseline:
			Provide 1–3 sentences which defines the baseline for which this bill impact is operating upon. Impact to society must be calculated relative to this baseline. End this section with one of the following machine-readble identifiers
			- CURRENT_LAW (name the law)
			- LIKELY_TRAJECTORY (absent new legislation)
			- NO_ACTION
			- OTHER (specify)
			
			2. Affected Parties:
			Provide 1-3 sentences describing exactly who the affected party(s) will be and how you determined it. End this section with one of the following machine-readble identifiers
			- VERY_NARROW (<0.1% of population)
			- NARROW (0.1%–1%)
			- MODERATE (1%–10%)
			- BROAD (10%–50%)
			- NEAR_UNIVERSAL (>50%)
			
			3. Directionality:
			Select one.
			- NET_BENEFICIAL
			- NET_HARMFUL
			- MIXED
			- UNCLEAR
			
			4. Effect Magnitude (per affected person):
			Provide 1-3 sentences describing the magnitude per affected person, and how you determined it. End this section with one of the following machine-readble identifiers
			- NEGLIGIBLE
			- MINOR
			- MODERATE
			- SIGNIFICANT
			- SEVERE
			
			5. Temporal Horizon:
			Provide 1-3 sentences describing the impact timeline and how you determined it. End this section with one of the following machine-readble identifiers
			- IMMEDIATE (0–5 years)
			- MEDIUM_TERM (5–20 years)
			- LONG_TERM (20+ years)
			
			6. Risk Structure:
			Provide 1-3 sentences describing the risk structure and how you determined it. End this section with one of the following machine-readble identifiers
			- LINEAR_EFFECTS
			- THRESHOLD_DEPENDENT
			- TAIL_RISK
			- SYSTEMIC_RISK
			- IRREVERSIBLE_HARM
			- REVERSIBLE_EFFECTS
			
			7. Reversibility:
			Provide 1-3 sentences describing the reversibility and how you determined it. End this section with one of the following machine-readble identifiers
			- EASILY_REVERSIBLE
			- DIFFICULT_TO_REVERSE
			- LARGELY_IRREVERSIBLE
			
			8. Summary:
			Provide a concise paragraph explaining:
			- the primary causal pathways
			- how impacts differ from the stated baseline
			- key assumptions
			- major uncertainties
			- any known thresholds, tipping points, or cumulative risks
			
			Long Report:
			Your audience is political enthusiasts and/or professionals, written at a college education level. Your first paragraph will explain the high level goals of the bill, give a high level summary of how it attempts to achieve those goals, and then conclude by giving an analysis on the impact the bill will have on society, if enacted. After your high-level summary, you will then go on to explain, in depth, how you came to these conclusions. You may conclude by identifying winners and losers of the bill, and identifying industry stakeholders, if relevant. Should be between three and seven paragraphs long, depending on the complexity of the bill and the topics it covers. If the bill touches on controversial topics such as trans issues or guns rights, please include the advocating logic by proponents and also the advocating logic of the opposition, otherwise do not include this logic. Where relevant, cite scientific studies or the opinions of authoritative knowledge sources to provide more context. If acronyms are referenced, which are not common knowledge, please define them.  Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Impact Stats:
			Score the following bill on the estimated impact to the United States upon the following criteria, rated from -100 (very harmful) to 0 (neutral) to +100 (very helpful) or N/A if it is not relevant. Scores near ±10 indicate marginal or localized effects; ±30 indicate meaningful policy impact; ±60 indicate major national consequences; ±90 indicate rare, transformative effects. Use the full scale sparingly and proportionally. There is an important distinction between 0 and N/A. Use 0 if the goal of the bill was to provide impact in the policy area - but you are predicting that it will have none. Use N/A if the goals of the bill do not align at all with the policy area. Respond with only the integer and an optional sign, no commentary or units.
			
			{issuesList}
			
			Rating:
			Write a single integer, from -100 to 100, which represents how strongly you think the legislation should be passed, with -100 being it definitely should NOT be passed and 100 being it definitely SHOULD be passed. This rating should directly translate to a grade for the bill, as per the following grading rubric (where r is the rating): A: r >= 60, B: 60 > r >= 40, C: 40 > r >= 20, D: 20 > r > 0, F: r <= 0. If the impact to society is negative, then so too should the rating score - naturally you wouldn't recommend voting for a bill which is bad for society. This rating metric is however separate from the impact scores, in that a bill might be of high rating but it might have a low impact. A common example might be a congressional gold medal award given for a heroic act (high rating), but the overall impact to society is small. Respond with only the integer, no commentary or units.
			
			{searchReferences}
			""";
	
	public static final String aggregatePrompt = """
			A large U.S. bill has been split into sections and summarized. Your role is to be a non-partisan oversight committee, evaluating whether or not the following bill will produce a positive overall benefit to society. In your response, fill out the sections as listed in the following template. Each section will have detailed instructions on how to fill it out. Make sure to include the section title (such as, 'Impact:') in your response. Do not include the section instructions in your response. Do not ever use 'I' language (as in, I reached this conclusion because...).
			
			{searchModelInstructions1}
			
			Neutral Summary:
			Read the bill text in its entirety and write a strictly neutral, non-partisan summary. This section must contain only officially verifiable information drawn from the bill text itself or widely accepted, non-partisan reference sources. The summary should be between one and three paragraphs, depending on the complexity of the bill. Start by describing what the bill does and the mechanisms it uses to achieve its stated objectives, including relevant thresholds, authorities, timelines, or structural changes where necessary. Avoid evaluative or predictive language. If relevant, briefly describe the stated rationale of supporters and the stated concerns of opponents, presenting both perspectives factually and symmetrically, without implying which is stronger or more correct. Your tone must remain strictly neutral and descriptive. Do not take sides, imply outcomes, assess impacts, or suggest whether the policy is good or bad. This section should be analogous in style and purpose to Congress.gov’s “Official Summary” and should be suitable for citation by journalists as reference-grade material. You may include links to neutral, non-partisan sources (for example, Congress.gov or Wikipedia) using markdown link syntax where appropriate. Do not include formatting symbols such as stars or dashes (excluding links). Do not include non-human-readable text such as XML identifiers, internal IDs, or metadata.
			
			Bill Title:
			Write the bill title. If the bill does not have a title and is only referred to by its bill number (such as HR 4141), please make up a very short title for the bill based on its content.
			
			Structural Analysis:
			Your goal in this section is to evaluate the bill across seven core pillars. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the pillar number, name and a colon, exactly as written here, followed by your analysis. Conclude each pillar analysis by writing either exactly "<PASS>", or "<FAIL>", denoting that the bill has either passed or failed that pillar of the structural analysis.
			
			1. Precision:
			Does the policy accurately diagnose the underlying issue and target the relevant causal mechanisms?
			2. Evidence:
			Is the proposed intervention supported by empirical research, historical precedent, or meaningful comparative data?
			3. Feasibility:
			Can existing institutions realistically execute the policy given resource, logistical, administrative, and temporal constraints?
			4. Budget:
			Does the policy use resources responsibly, minimize waste, and avoid unsustainable long-term obligations?
			5. Fairness:
			How are benefits and burdens distributed across populations, and does the policy unjustifiably disadvantage certain groups?
			6. Governance:
			Does the policy maintain transparency, accountability, and resilience while minimizing opportunities for corruption or abuse?
			7. Risk:
			Does the policy introduce fragility, perverse incentives, or cascading failures that undermine the intended outcomes?
			
			Long Report:
			Your audience is political enthusiasts and/or professionals, written at a college education level. Your first paragraph will explain the high level goals of the bill, give a high level summary of how it attempts to achieve those goals, and then conclude by giving an analysis on the impact the bill will have on society, if enacted. After your high-level summary, you will then go on to explain, in depth, how you came to these conclusions. You may conclude by identifying winners and losers of the bill, and identifying industry stakeholders, if relevant. Should be between three and seven paragraphs long, depending on the complexity of the bill and the topics it covers. If the bill touches on controversial topics such as trans issues or guns rights, please include the advocating logic by proponents and also the advocating logic of the opposition, otherwise do not include this logic. Where relevant, cite scientific studies or the opinions of authoritative knowledge sources to provide more context. If acronyms are referenced, which are not common knowledge, please define them.  Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Confidence:
			A self-rated integer from 0 to 100 measuring how confident you are that your analysis was valid and interpreted correctly.
			
			Rating:
			Write a single integer, from -100 to 100, which represents how strongly you think the legislation should be passed, with -100 being it definitely should NOT be passed and 100 being it definitely SHOULD be passed. This rating should directly translate to a grade for the bill, as per the following grading rubric (where r is the rating): A: r >= 60, B: 60 > r >= 40, C: 40 > r >= 20, D: 20 > r > 0, F: r <= 0. If the impact to society is negative, then so too should the rating score - naturally you wouldn't recommend voting for a bill which is bad for society. This rating metric is however separate from the impact scores, in that a bill might be of high rating but it might have a low impact. A common example might be a congressional gold medal award given for a heroic act (high rating), but the overall impact to society is small. Respond with only the integer, no commentary or units.
			
			Casual Report:
			Your audience is the general public, written at the high-school education level. Your primary goal here is to take the insights from the long report and make them available for your average person. Begin by explaining the high level goals of the bill and a high level summary of how it attempts to achieve those goals. Then, explain all the essential logic required to understand your predicted impact to society, including any research, expert opinions, societal wisdom and/or any competing concerns which may exist. Conclude with your findings on the impact the bill will have on society. Should be between one and three paragraphs long, depending on the complexity of the bill and the topics it covers (controversial topics require more evidence to support your conclusions). Do not use acronyms, such as GPO, CBO, etc. If you must use them, they must be defined. You may link to sources using markdown link syntax, where relevant. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.{searchModelInstructions2}
			
			Short Report:
			Your audience is the general public, written at the high-school education level. A single paragraph, at least four sentence report which gives a detailed, but not repetitive, summary of the bill, any high level goals, and its expected impact to society. Do not include any formatting text such as stars or dashes (excluding links). Do not include non-human readable text such as XML ids.
			
			{searchReferences}
			""";
	
	public static final String statsPrompt;
	public static final String slicePrompt;
	static {
		String issues = String.join("\n", Arrays.stream(TrackedIssue.values()).map(issue -> issue.getName() + ": <score or N/A>").toList());
    	statsPrompt = statsPromptTemplate.replaceFirst("\\{issuesList\\}", issues);
    	slicePrompt = slicePromptTemplate.replaceFirst("\\{issuesList\\}", issues);
	}
	
	public static final String SEARCH_MODEL_INSTRUCTIONS1 = """
Supplemental Web Search:
This is your first section, and your goal is to query the web and gather additional supplemental information, which may or may not exist. You are to fill out each step in your response, thinking carefully at each step. Begin your response with the step number, name and a colon, exactly as written here, followed by your search results. If you do not have access to the web, output 'Unable to access the web' and end this section; NEVER make up fake sources. Information for each of these sections may or may not exist; if it cannot be found, it must not be treated as evidence for or against the bill.

1. Advocate / Sponsor Reasoning:
Gather any information which may have been published on the web from the bill's sponsor or likely advocates. We're looking here for rationale which might explain their goals and reasoning process.
2. Opposition Reasoning:
Gather any information which may have been published on the web from the bill's opposition. We're looking here for rationale which might explain their goals and reasoning process.
3. Budget Report:
Search to see if a budgetary analysis by a credible organization has been published for this bill. If this is a congressional bill, search for a CBO report. if it's a state legislature then find the equivalent organization (if one exists). If a credible budgetary analysis was found, write the findings here.
4. Media Analysis:
Search for existing analysis which may have been published by a news organization. Be careful to avoid introducing partisan bias into the analysis in this process.
5. Identify Broader Context:
Attempt to identify the broader context in which this bill exists, and any larger strategy or context it may be a part of. For example sometimes a bill can be a response to sub-optimal choices made elsewhere.
			""";
	public static final String SEARCH_MODEL_INSTRUCTIONS2 = " Where appropriate, please cite references from your search inside the report. References can be cited using markdown link syntax: [explanation text here](http://example.com)";
	public static final String SEARCH_REFERENCES = """
Search References:
Output the header (Search References:) and exactly one line that is valid JSON and nothing else.

Required JSON shape - The output must be a JSON array. Each element must be a JSON array of exactly 8 strings, in this exact order:
[url, author, title, authors_opinion_int_string, authors_opinion_text, summary, long_summary, reference_type]

1. url: full URL string
2. author: author name or "Unknown"
3. title: article/page title
4. authors_opinion_int_string: a string integer from "-100" to "100" representing the author's opinion on the bill, either positive (in favor) or negative (against).
5. authors_opinion_text: one of: "N/A" | "Mixed" | "Neutral" | "For" | "Against". Again, represents the author's opinion, either for or against, the bill itself.
6. summary: 1–2 sentence concise summary of why this reference mattered
7. long_summary: must contain all critical information from the article that was actually used in the analysis (include key findings, numbers, constraints, definitions, conclusions, and any caveats that materially affected reasoning). It should be detailed enough that a reader can understand exactly what evidence was used without opening the link.
8. reference_type: one of: "STAKEHOLDER" | "BUDGETARY" | "NEWS" | "ACADEMIC" | "LEGAL" | "GOVERNMENT" | "OTHER"

Hard rules (to ensure machine readability)
1. Output must be valid JSON per JSON.parse(...).
2. Output must be a JSON array, not an object.
3. Do not wrap the JSON in quotes. Never output “stringified JSON.”
  Bad: "[["https://..."]]"
  Bad: ["[\"https://...\"]"]
  Good: [[ "https://...", ... ]]
4. Do not include markdown, code fences, labels, commentary, or extra whitespace lines.
5. Use only standard JSON double quotes " (no smart quotes).
6. No trailing commas.
7. Do not include newline characters outside JSON string escaping. (If newlines are needed inside long_summary, encode them as \n.)
8. If no references were used, output exactly: []
9. Each top-level element must be a JSON array, not a string

Content rule (important)
Only include references that were actually used as evidence in the analysis (not links that were merely found but not used). If an item is mentioned in the analysis, it must appear here; if it is not used, it must not appear here.

Example (format reference only)
[["https://pmc.ncbi.nlm.nih.gov/articles/PMC9677302/","Sajjad et al., Canadian Journal of Kidney Health and Disease, 2022","Motivators and Barriers to Living Donor Kidney Transplant as Perceived by Past and Potential Donors","0","N/A","Peer-reviewed study citing lack of job security as a significant barrier to living donation.","A large proportion of women and men reported that guaranteed job security (47% women and 38% of men), paid time off (51% of women and 42% of men), reimbursement of lost wages (49% of women and 38% of men), and protections to guarantee no impact on future insurability (62% of women and 52% of men) were significant motivators to donate.","ACADEMIC"]]
 
 Before printing the final line, internally verify the output is a JSON array of arrays, each inner array has length 8, and every element is a string; if not, correct it until it passes.
			""";
//	public static final String SEARCH_REFERENCES = "Search References:\nA single line JSON array payload which contains machine readable data about all the references used in your analysis. Each reference shall be represented by a JSON array with the following string fields: [\"https://example.org/full/url/here\", \"author or Unknown\", \"title\", \"sentiment as an integer from -100 to 100\", \"sentiment text: a textual representation of the sentiment. Could be N/A, Mixed, Neutral, Positive, Negative, etc.\", \"summary\", \"long summary\",  \"reference type, one of : STAKEHOLDER | BUDGETARY | NEWS | ACADEMIC | LEGAL | GOVERNMENT | OTHER\"]\n"
//			+ "The long summary field must contain all critical information from the article which you used in your analysis. Here is an example of a single cited reference (you may have more than one): [[\"https://pmc.ncbi.nlm.nih.gov/articles/PMC9677302/\", \"Sajjad et al., Canadian Journal of Kidney Health and Disease, 2022\", \"Motivators and Barriers to Living Donor Kidney Transplant as Perceived by Past and Potential Donors\", \"0\", \"N/A\", \"Peer-reviewed study citing lack of job security as a significant barrier to living donation.\", \"A large proportion of women and men reported that guaranteed job security (47% women and 38% of men), paid time off (51% of women and 42% of men), reimbursement of lost wages (49% of women and 38% of men), and protections to guarantee no impact on future insurability (62% of women and 52% of men) were significant motivators to donate.\", \"ACADEMIC\"]]";
	
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
		
		if (!DatabaseBuilder.AGENTIC_WEB_SEARCH) {
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
