package us.poliscore.model.bill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.val;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.IssueStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.service.storage.S3PersistenceService;

public class BillInterpretationParser {
	
	private static Logger logger = LoggerFactory.getLogger(BillInterpretationParser.class);
	
	public static List<String> summaryHeader = Arrays.asList("summary:", "*summary:*", "**summary:**", "*summary*", "**summary**");
	
	private State state = null;
	
	private BillInterpretation interp;
	
	private S3PersistenceService s3;
	
	public static enum State {
		REASONING("(?i)Reasoning Steps:"),
		SEARCH_REFERENCES("(?i)Search References:"),
		STATS("(?i)Stats:"),
		AUTHOR("(?i)Author:"),
		TITLE("(?i)Title:", "(?i)Bill Title:"),
		RIDERS("(?i)Riders:"),
		SHORT_REPORT("(?i)Short Report:"),
		LONG_REPORT("(?i)Long Report:"),
		CONFIDENCE("(?i)Confidence:");
		
		private List<String> regex;
		
		private State(String ...regex) {
			this.regex = Arrays.asList(regex);
		}
		
//		public boolean matches(String line) {
//			return regex.stream().map(r -> line.matches(r)).reduce(false, (a,b) -> a || b);
//		}
	}
	
	public BillInterpretationParser(BillInterpretation interp, S3PersistenceService s3) {
		this.interp = interp;
		this.s3 = s3;
	}
	
	public void parse(String text) {
		interp.setSearchReferences("");
		interp.setReasoning("");
		interp.setShortExplain("");
		interp.setLongExplain("");
		interp.setAuthor("");
		interp.setRiders(new ArrayList<String>());
		interp.setIssueStats(new IssueStats());
		interp.setConfidence(-1);
		
		try (final Scanner scanner = new Scanner(text))
		{
			while (scanner.hasNextLine())
			{
			  String line = standardizeFormatting(scanner.nextLine());
			  
			  if (StringUtils.isBlank(line) || setState(line) || state == null) continue;
			  
			  processContent(line);
			}
		}
		
		
		// TODO : Clean?
		
		validateIssueStats(interp.getIssueStats());
	}
	
	private String standardizeFormatting(String line) {
	    if (line == null) return null;

	    return line
	        // Normalize dashes to hyphen-minus
	        .replace("–", "-").replace("—", "-").replace("−", "-")
	        .replace("\u2010", "-").replace("\u2013", "-").replace("\u2014", "-").replace("\u2212", "-")

	        // Normalize pluses to '+'
	        .replace("＋", "+").replace("\uFF0B", "+")

	        // Normalize quotes
	        .replace("“", "\"").replace("”", "\"")
	        .replace("‘", "'").replace("’", "'")

	        // Normalize full-width punctuation
	        .replace("．", ".").replace("，", ",").replace("：", ":")

	        // Normalize non-breaking/narrow spaces to normal space
	        .replaceAll("[\\u00A0\\u2007\\u202F]", " ")

	        // Standardize N/A variants
	        .replaceAll("(?i)\\b(n\\s*/\\s*a)\\b", "N/A")

	        // Collapse multiple spaces
	        .replaceAll("\\s+", " ")

	        .trim();
	}
	
	private void processContent(String line) {
		if (State.STATS.equals(state)) {
			processStat(line);
		} else if (State.AUTHOR.equals(state)) {
			processAuthor(line);
		} else if (State.TITLE.equals(state)) {
			processTitle(line);
		} else if (State.RIDERS.equals(state)) {
			processRider(line);
		} else if (State.SHORT_REPORT.equals(state)) {
			processShortForm(line);
		} else if (State.LONG_REPORT.equals(state)) {
			processLongForm(line);
		} else if (State.CONFIDENCE.equals(state)) {
			processConfidence(line);
		} else if (State.REASONING.equals(state)) {
			processReasoning(line);
		} else if (State.SEARCH_REFERENCES.equals(state)) {
			processSearchReferences(line);
		}
	}
	
	private void validateIssueStats(IssueStats stats) {
	    int zeroCount = 0;
	    int totalSet = 0;
	    for (TrackedIssue issue : TrackedIssue.values()) {
	    	if (issue != TrackedIssue.OverallBenefitToSociety && stats.hasStat(issue)) {
	            totalSet++;
	            if (stats.getStat(issue) == 0) zeroCount++;
	        }
	    }

	    if (Math.abs(totalSet - TrackedIssue.values().length) <= 2 && zeroCount > 1) {
	    	logger.error("Malformed AI response for bill [" + this.interp.billId + "]: too many tracked issues were assigned a value of 0. Only include an issue if it is truly relevant. Zeros will be removed from issue stats.");
	    	
	    	for (TrackedIssue issue : TrackedIssue.values()) {
		        if (stats.hasStat(issue) && stats.getStat(issue) == 0 && issue != TrackedIssue.OverallBenefitToSociety) {
		        	stats.removeStat(issue);
		        }
		    }
	    }
	}

	
	private void clean(String dirty) {
//		val summaryHeaders = new String[] { "summary of the predicted impact to society and why", "summary of the predicted impact to society", "summary of the bill and predicted impact to society and why", "summary of the bill and predicted impact to society", "summary of the bill and its predicted impact to society and why", "summary of the bill and its predicted impact to society", "Summary of the bill's predicted impact to society and why", "Summary of the bill's predicted impact to society", "summary of predicted impact to society and why", "summary of predicted impact to society", "summary of the impact to society", "summary of impact to society", "summary report", "summary of the impact", "summary of impact", "summary", "explanation" };
//		val summaryHeaderRegex = " *#*\\** *(" + String.join("|", summaryHeaders) + ") *#*\\** *:? *#*\\** *";
//		if (stats.explanation.matches("(?i)^" + summaryHeaderRegex + ".*$")) {
//			stats.explanation = stats.explanation.replaceFirst("(?i)" + summaryHeaderRegex, "");
//		}
	}
	
	private void processReasoning(String line) {
		String newline = StringUtils.isBlank(interp.getReasoning()) ? "" : "\n";
		interp.setReasoning(interp.getReasoning() + newline + line);
	}
	
	private void processSearchReferences(String line) {
		String newline = StringUtils.isBlank(interp.getReasoning()) ? "" : "\n";
		interp.setSearchReferences(interp.getSearchReferences() + newline + line);
		
		try
		{
			line = line.replace(" - ", "").replace("- ", "").replace(" -", "").replace("-", "").replace("\\\"", "");
			
			// [\"https://example.org/full/url/here\", \"author\", \"title\", \"sentiment as an integer from -100 to 100\", \"summary\"]
			String[] values = new ObjectMapper().readValue(line, new TypeReference<String[]>() {});
			
			val url = values[0];
			val author = values[1];
			val title = values[2];
			val sentiment = parseSentiment(values[3]);
			val summary = values[4];
			
			val origin = new InterpretationOrigin(url, title);
			
			val pi = new PressInterpretation();
			pi.setBillId(interp.getBillId());
			pi.setOrigin(origin);
			pi.setMetadata(interp.getMetadata());
			pi.setId(PressInterpretation.generateId(interp.getBillId(), origin));
			pi.setNoInterp(false);
			pi.setAuthor(author);
			pi.setGenArticleTitle(title);
			pi.setSentiment(sentiment);
			pi.setShortExplain(summary);
			s3.put(pi);
			
			interp.getPressInterps().add(pi);
		}
		catch(Throwable t) {
			logger.error("Error parsing search reference", t);
		}
	}
	
	private int parseSentiment(String sentimentStr) {
	    if (StringUtils.isBlank(sentimentStr)) return 0;
	    
	    try {
	    	return Integer.parseInt(sentimentStr);
	    } catch (Throwable t) {
	    	// Ignore
	    }

	    String normalized = sentimentStr.toLowerCase().trim();

	    if (normalized.contains("mixed")) return 0;
	    if (normalized.contains("neutral")) return 0;
	    if (normalized.contains("analytical")) return 0;

	    if (normalized.contains("supportive") || normalized.contains("positive") || normalized.contains("endorse")) return 75;
	    if (normalized.contains("strongly supportive") || normalized.contains("enthusiastic")) return 100;

	    if (normalized.contains("critical") || normalized.contains("negative")) return -75;
	    if (normalized.contains("strongly critical") || normalized.contains("condemn")) return -100;

	    // Fallback for unknown/ambiguous sentiment
	    return 0;
	}
	
	private void processStat(String line) {
		Pair<TrackedIssue, Integer> stat = IssueStats.parseStat(line);
		  
		if (stat != null && stat.getRight() != IssueStats.NA)
		{
			interp.getIssueStats().setStat(stat.getLeft(), stat.getRight());
		}
	}
	
	private void processConfidence(String line) {
		try
		{
			line = line.replaceAll("%", "").strip();
			
			if (line.contains("."))
				interp.setConfidence(Math.round(Float.parseFloat(line)*100.0f));
			else
				interp.setConfidence(Integer.parseInt(line));
		}
		catch (Throwable t)
		{
			logger.error("Error setting confidence", t);
		}
	}
	
	private void processAuthor(String line) {
		if (!line.toLowerCase().equals("n/a"))
			interp.setAuthor(line);
	}
	
	private void processTitle(String line) {
		interp.setGenBillTitle(line);
	}
	
	private void processRider(String line) {
		if (line.matches("^ ?- ?.+$")) {
			line = line.replaceFirst(" ?- ?", "");
		} else if (line.matches("^ ?\\d\\.? ?.+$")) {
			line = line.replaceFirst(" ?\\d\\.? ?", "");
		}
		
		if (line.strip().toLowerCase().equals("none")) return;
		
		interp.getRiders().add(line);
	}
	
	private void processLongForm(String line) {
		interp.setLongExplain(interp.getLongExplain() + "\n" + line);
	}
	
	private void processShortForm(String line) {
		interp.setShortExplain(interp.getShortExplain() + "\n" + line);
	}
	
	private boolean setState(String line) {
	    for (State s : State.values()) {
	        for (String regex : s.regex) {
	            if (line.matches(regex + ".*")) {
	                state = s;

	                // Handle inline content (e.g., "Title: This is a title")
	                String inlineContent = line.replaceFirst(regex, "").strip();
	                if (!inlineContent.isEmpty()) {
	                    processContent(inlineContent);
	                }

	                return true;
	            }
	        }
	    }

	    return false;
	}
	
}
