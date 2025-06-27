package us.poliscore.model.press;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import us.poliscore.model.IssueStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillInterpretationParser;

public class PressInterpretationParser {
	
	private static Logger logger = LoggerFactory.getLogger(PressInterpretationParser.class);
	
	public static List<String> summaryHeader = Arrays.asList("summary:", "*summary:*", "**summary:**", "*summary*", "**summary**");
	
	private State state = null;
	
	private PressInterpretation interp;
	
	public static enum State {
		SENTIMENT("(?i)Sentiment:"),
		AUTHOR("(?i)Author:"),
		TITLE("(?i)Title:", "(?i)Article Title:"),
		SHORT_REPORT("(?i)Short Report:"),
		LONG_REPORT("(?i)Long Report:"),
		CONFIDENCE("(?i)Confidence:");
		
		private List<String> regex;
		
		private State(String ...regex) {
			this.regex = Arrays.asList(regex);
		}
	}
	
	public PressInterpretationParser(PressInterpretation interp) {
		this.interp = interp;
	}
	
	public void parse(String text) {
		interp.setShortExplain("");
		interp.setLongExplain("");
		interp.setGenArticleTitle("");
		interp.setSentiment(Integer.MIN_VALUE);
		interp.setAuthor("");
		interp.setConfidence(-1);
		
		try (final Scanner scanner = new Scanner(text))
		{
			while (scanner.hasNextLine())
			{
			  String line = scanner.nextLine().strip();
			  
			  if (StringUtils.isBlank(line) || setState(line) || state == null) continue;
			  
			  processContent(line);
			}
		}
	}
	
	private void processContent(String line) {
		if (State.SENTIMENT.equals(state)) {
			processSentiment(line);
		} else if (State.AUTHOR.equals(state)) {
			processAuthor(line);
		} else if (State.TITLE.equals(state)) {
			processTitle(line);
		} else if (State.SHORT_REPORT.equals(state)) {
			processShortForm(line);
		} else if (State.LONG_REPORT.equals(state)) {
			processLongForm(line);
		} else if (State.CONFIDENCE.equals(state)) {
			processConfidence(line);
		}
	}
	
	private void processConfidence(String line) {
		line = stripNumericLeads(line);
		
		try {
			// Extract only the first integer (handles things like "-20 (explanation)")
			String numericPart = line.trim().split("\\s+")[0];
			
			interp.setConfidence(Integer.parseInt(numericPart));
		} catch (Throwable t) {
			logger.error("Error setting confidence", t);
		}
	}
	
	private void processSentiment(String line) {
		line = stripNumericLeads(line);
		
		try {
			// Extract only the first integer (handles things like "-20 (explanation)")
			String numericPart = line.trim().split("\\s+")[0];
			
			interp.setSentiment(Integer.parseInt(numericPart));
		} catch (Throwable t) {
			logger.error("Error setting sentiment", t);
		}
	}
	
	/**
	 * Someitmes ChatGPT will return a number as "Positive 40" or "Negative 40". Here we just strip that and make it more predictable, i.e. -40, or 40.
	 * @param line
	 * @return
	 */
	private String stripNumericLeads(String line) {
		line = line.trim();
		
		if (line.startsWith("Positive")) line = line.replaceFirst("Positive", "");
		if (line.startsWith("positive")) line = line.replaceFirst("positive", "");
		if (line.startsWith("Negative")) line = line.replaceFirst("Negative", "");
		if (line.startsWith("negative")) line = line.replaceFirst("negative", "");
		
		return line;
	}
	
	private void processAuthor(String line) {
		if (!line.toLowerCase().equals("n/a"))
			interp.setAuthor(line);
	}
	
	private void processTitle(String line) {
		interp.setGenArticleTitle(line);
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
