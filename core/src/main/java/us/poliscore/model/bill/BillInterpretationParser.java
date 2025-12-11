package us.poliscore.model.bill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.val;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.InterpretationOrigin.InvalidOriginException;
import us.poliscore.model.IssueStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.service.storage.S3PersistenceService;

public class BillInterpretationParser {

	private static Logger logger = LoggerFactory.getLogger(BillInterpretationParser.class);

	public static List<String> summaryHeader = Arrays.asList("summary:", "*summary:*", "**summary:**", "*summary*",
			"**summary**");

	private static final Pattern LEADING_DASHES = Pattern.compile("^[\\p{Pd}\\u2212\\s]*");

	private State state = null;

	private Bill bill;

	private BillInterpretation interp;

	private S3PersistenceService s3;

	public static enum State {
		REASONING("(?i)Reasoning Steps:"), STRUCTURAL("(?i)Structural Analysis:"),
		SEARCH_REFERENCES("(?i)Search References:"), IMPACT("(?i)Impact:"), RATING("(?i)Rating:"),
		AUTHOR("(?i)Author:"), TITLE("(?i)Title:", "(?i)Bill Title:"), RIDERS("(?i)Riders:"),
		SHORT_REPORT("(?i)Short Report:"), LONG_REPORT("(?i)Long Report:"), LAYMANS_REPORT("(?i)Casual Report:"),
		CONFIDENCE("(?i)Confidence:");

		private List<String> regex;

		private State(String... regex) {
			this.regex = Arrays.asList(regex);
		}

//		public boolean matches(String line) {
//			return regex.stream().map(r -> line.matches(r)).reduce(false, (a,b) -> a || b);
//		}
	}

	public BillInterpretationParser(Bill bill, BillInterpretation interp, S3PersistenceService s3) {
		this.bill = bill;
		this.interp = interp;
		this.s3 = s3;
	}

	public void parse(String text, String reasoning) {
		interp.setRating(0);
		interp.setSearchReferences("");
		interp.setStructuralAnalysisRaw("");
		interp.setShortExplain("");
		interp.setLongExplain("");
		interp.setAuthor("");
		interp.setRiders(new ArrayList<String>());
		interp.setIssueStats(new IssueStats());
		interp.setConfidence(-1);
		interp.setLaymansReport("");
		
		if (StringUtils.isNotEmpty(reasoning))
			interp.setReasoning(reasoning);
		else
			interp.setReasoning("");

		try (final Scanner scanner = new Scanner(text)) {
			while (scanner.hasNextLine()) {
				String line = scanner.nextLine().trim();

				if (StringUtils.isBlank(line) || setState(line) || state == null)
					continue;

				processContent(line);
			}
		}

		// TODO : Clean?

		validateIssueStats(interp.getIssueStats());

		// Parse structural analysis into clean outputs
		try {
            if (StringUtils.isNotBlank(interp.getStructuralAnalysisRaw())) {
                StructuralAnalysisParser.StructuralAnalysisParsed saParsed =
                        StructuralAnalysisParser.parse(interp.getStructuralAnalysisRaw());

                interp.setStructuralAnalysisPassFail(saParsed.getResults());
                interp.setStructuralAnalysisExplain(saParsed.getAnalyses());
            }
        } catch (Throwable t) {
            logger.error("Error parsing structural analysis results for bill [" + this.interp.billId + "]", t);
        }
	}

	private String standardizeFormatting(String line) {
		if (line == null)
			return null;

		return line
				// Normalize dashes to hyphen-minus
				.replace("–", "-").replace("—", "-").replace("−", "-").replace("\u2010", "-").replace("\u2013", "-")
				.replace("\u2014", "-").replace("\u2212", "-")

				// Normalize pluses to '+'
				.replace("＋", "+").replace("\uFF0B", "+")

				// Normalize quotes
				.replace("“", "\"").replace("”", "\"").replace("‘", "'").replace("’", "'")

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
		if (!ArrayUtils.contains(
				new State[] { State.LONG_REPORT, State.SHORT_REPORT, State.LAYMANS_REPORT, State.SEARCH_REFERENCES },
				state)) {
			line = standardizeFormatting(line);
		}

		if (State.IMPACT.equals(state)) {
			processStat(line);
		} else if (State.RATING.equals(state)) {
			processRating(line);
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
		} else if (State.STRUCTURAL.equals(state)) {
			processStructuralAnalysis(line);
		} else if (State.SEARCH_REFERENCES.equals(state)) {
			processSearchReferences(line);
		} else if (State.LAYMANS_REPORT.equals(state)) {
			processLaymansReport(line);
		}
	}

	private void validateIssueStats(IssueStats stats) {
		int zeroCount = 0;
		int totalSet = 0;
		for (TrackedIssue issue : TrackedIssue.values()) {
			if (issue != TrackedIssue.OverallBenefitToSociety && stats.hasStat(issue)) {
				totalSet++;
				if (stats.getStat(issue) == 0)
					zeroCount++;
			}
		}

		if (Math.abs(totalSet - TrackedIssue.values().length) <= 2 && zeroCount > 1) {
			logger.error("Malformed AI response for bill [" + this.interp.billId
					+ "]: too many tracked issues were assigned a value of 0. Only include an issue if it is truly relevant. Zeros will be removed from issue stats.");

			for (TrackedIssue issue : TrackedIssue.values()) {
				if (stats.hasStat(issue) && stats.getStat(issue) == 0
						&& issue != TrackedIssue.OverallBenefitToSociety) {
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

	private void processStructuralAnalysis(String line) {
		String newline = StringUtils.isBlank(interp.getStructuralAnalysisRaw()) ? "" : "\n";
		interp.setStructuralAnalysisRaw(interp.getStructuralAnalysisRaw() + newline + line);
	}

	private void processSearchReferences(String line) {
		// Even though we asked openai to not give us newlines, sometimes it does anyway
		if (line.strip().equals("[") || line.strip().equals("]"))
			return;

		String newline = StringUtils.isBlank(interp.getReasoning()) ? "" : "\n";
		interp.setSearchReferences(interp.getSearchReferences() + newline + line);

		try {
			// Remove leading dash-like characters (hyphen, en dash, em dash, minus, etc.)
			line = LEADING_DASHES.matcher(line).replaceFirst("");

			// Also remove escaped quotes (from legacy issues?)
			line = line.replace("\\\"", "");

			try {
				String[][] references = new ObjectMapper().readValue(line, new TypeReference<String[][]>() {
				});

				for (val values : references) {
					try {
						processSearchReference(values);
					} catch (InvalidOriginException e) {
						// Url validation failed
					}
				}
			} catch (JsonProcessingException t) {
				// Even though we asked openai to not give us newlines, sometimes it does anyway
				String[] values = new ObjectMapper().readValue(line, new TypeReference<String[]>() {
				});

				processSearchReference(values);
			}
		} catch (Throwable t) {
			logger.error("Error parsing search reference", t);
		}
	}

	private void processSearchReference(String[] values) {
		val url = values[0];
		val author = values[1];
		val title = values[2];
		val sentiment = parseSentiment(values[3]);
		val sentimentText = values[4];
		val shortExplain = values[5];
		val longExplain = values[6];
		val type = values[7];

		val origin = new InterpretationOrigin(url, title);
		origin.validate(interp.getBill().getOfficialUrl());

		val pi = new PressInterpretation();
		pi.setBillId(interp.getBillId());
		pi.setOrigin(origin);
		pi.setMetadata(interp.getMetadata());
		pi.setId(PressInterpretation.generateId(interp.getBillId(), origin));
		pi.setNoInterp(false);
		pi.setAuthor(author);
		pi.setGenArticleTitle(title);
		pi.setSentiment(sentiment);
		pi.setSentimentText(sentimentText);
		pi.setShortExplain(shortExplain);
		pi.setLongExplain(longExplain);
		pi.setType(type);
		s3.put(pi);

		interp.getPressInterps().add(pi);
	}

	private int parseSentiment(String sentimentStr) {
		if (StringUtils.isBlank(sentimentStr))
			return 0;

		try {
			return Integer.parseInt(sentimentStr);
		} catch (Throwable t) {
			// Ignore
		}

		String normalized = sentimentStr.toLowerCase().trim();

		if (normalized.contains("mixed"))
			return 0;
		if (normalized.contains("neutral"))
			return 0;
		if (normalized.contains("analytical"))
			return 0;

		if (normalized.contains("supportive") || normalized.contains("positive") || normalized.contains("endorse"))
			return 75;
		if (normalized.contains("strongly supportive") || normalized.contains("enthusiastic"))
			return 100;

		if (normalized.contains("critical") || normalized.contains("negative"))
			return -75;
		if (normalized.contains("strongly critical") || normalized.contains("condemn"))
			return -100;

		// Fallback for unknown/ambiguous sentiment
		return 0;
	}

	private void processStat(String line) {
		Pair<TrackedIssue, Integer> stat = IssueStats.parseStat(line);

		if (stat != null && stat.getRight() != IssueStats.NA) {
			interp.getIssueStats().setStat(stat.getLeft(), stat.getRight());
		}
	}

	private void processRating(String line) {
		try {
			line = line.replaceAll("%", "").strip();

			if (line.contains("."))
				interp.setRating(Math.round(Float.parseFloat(line) * 100.0f));
			else
				interp.setRating(Integer.parseInt(line));
		} catch (Throwable t) {
			logger.error("Error setting quality", t);
		}
	}

	private void processConfidence(String line) {
		try {
			line = line.replaceAll("%", "").strip();

			if (line.contains("."))
				interp.setConfidence(Math.round(Float.parseFloat(line) * 100.0f));
			else
				interp.setConfidence(Integer.parseInt(line));
		} catch (Throwable t) {
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

		if (line.strip().toLowerCase().equals("none"))
			return;

		interp.getRiders().add(line);
	}

	private void processLongForm(String line) {
		interp.setLongExplain(interp.getLongExplain() + "\n" + line);
	}

	private void processLaymansReport(String line) {
		interp.setLaymansReport(interp.getLaymansReport() + "\n" + line);
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

	/**
	 * Parses the "Structural Analysis" free-form section into: -
	 * Map<StructuralAnalysis, Boolean> (PASS = true, FAIL = false) -
	 * Map<StructuralAnalysis, String> (analysis text with PASS/FAIL stripped)
	 *
	 * Expected format (per prompt):
	 *
	 * 1. Problem Clarity & Causal Validity: ...analysis... <PASS> or <FAIL>
	 *
	 * 2. Evidence Base & Empirical Support: ...analysis... <PASS> or <FAIL>
	 *
	 * ... etc up through pillar 7.
	 */
	public static class StructuralAnalysisParser {

		// Matches the numbered pillar headers like:
		// "1. Problem Clarity & Causal Validity:"
		// "2. Evidence Base & Empirical Support"
		private static final Pattern PILLAR_HEADER_PATTERN = Pattern.compile("(?m)^\\s*([1-7])\\.\\s*([^\\n]*)");

		// Matches <PASS> or <FAIL> anywhere in the pillar body
		private static final Pattern PASS_FAIL_PATTERN = Pattern.compile("(?i)<\\s*(PASS|FAIL)\\s*>");

		private StructuralAnalysisParser() {
			// utility
		}

		public static class StructuralAnalysisParsed {
			private final Map<StructuralAnalysis, Boolean> results;
			private final Map<StructuralAnalysis, String> analyses;

			public StructuralAnalysisParsed(Map<StructuralAnalysis, Boolean> results,
					Map<StructuralAnalysis, String> analyses) {
				this.results = results;
				this.analyses = analyses;
			}

			public Map<StructuralAnalysis, Boolean> getResults() {
				return results;
			}

			public Map<StructuralAnalysis, String> getAnalyses() {
				return analyses;
			}
		}

		/**
		 * Parse the full structural analysis text (all 7 pillars) into a pair of maps.
		 */
		public static StructuralAnalysisParsed parse(String structuralAnalysisText) {
			if (StringUtils.isBlank(structuralAnalysisText)) {
				return new StructuralAnalysisParsed(Collections.emptyMap(), Collections.emptyMap());
			}

			String text = structuralAnalysisText.replace("\r\n", "\n");
			Map<StructuralAnalysis, Boolean> results = new HashMap<>();
			Map<StructuralAnalysis, String> analyses = new HashMap<>();

			Matcher matcher = PILLAR_HEADER_PATTERN.matcher(text);

			StructuralAnalysis currentPillar = null;
			int currentBodyStart = -1;

			while (matcher.find()) {
				// Close off previous pillar
				if (currentPillar != null && currentBodyStart >= 0) {
					String body = text.substring(currentBodyStart, matcher.start()).trim();
					StructuralAnalysisEntry entry = parseBody(body);
					if (entry.passFail != null) {
						results.put(currentPillar, entry.passFail);
					}
					if (StringUtils.isNotBlank(entry.cleanedAnalysis)) {
						analyses.put(currentPillar, entry.cleanedAnalysis);
					}
				}

				int number = Integer.parseInt(matcher.group(1));
				try {
					currentPillar = StructuralAnalysis.fromNumber(number);
				} catch (IllegalArgumentException e) {
					LoggerFactory.getLogger(BillInterpretationParser.class)
							.warn("Unknown structural analysis pillar number: {}", number, e);
					currentPillar = null;
				}
				currentBodyStart = matcher.end();
			}

			// Handle trailing pillar body (after last header)
			if (currentPillar != null && currentBodyStart >= 0 && currentBodyStart < text.length()) {
				String body = text.substring(currentBodyStart).trim();
				StructuralAnalysisEntry entry = parseBody(body);
				if (entry.passFail != null) {
					results.put(currentPillar, entry.passFail);
				}
				if (StringUtils.isNotBlank(entry.cleanedAnalysis)) {
					analyses.put(currentPillar, entry.cleanedAnalysis);
				}
			}

			return new StructuralAnalysisParsed(results, analyses);
		}

		/**
		 * Holds both the boolean result and cleaned text for a single pillar.
		 */
		private static class StructuralAnalysisEntry {
			final Boolean passFail;
			final String cleanedAnalysis;

			StructuralAnalysisEntry(Boolean passFail, String cleanedAnalysis) {
				this.passFail = passFail;
				this.cleanedAnalysis = cleanedAnalysis;
			}
		}

		/**
		 * Extracts the last-occurring <PASS> or <FAIL> from a pillar's body and returns
		 * that boolean plus the analysis text with all PASS/FAIL tags removed.
		 */
		private static StructuralAnalysisEntry parseBody(String body) {
			if (StringUtils.isBlank(body)) {
				return new StructuralAnalysisEntry(null, null);
			}

			Matcher m = PASS_FAIL_PATTERN.matcher(body);
			Boolean last = null;

			while (m.find()) {
				String token = m.group(1).toUpperCase();
				if ("PASS".equals(token)) {
					last = Boolean.TRUE;
				} else if ("FAIL".equals(token)) {
					last = Boolean.FALSE;
				}
			}

			// Strip all <PASS> / <FAIL> tokens from the analysis text
			String cleaned = PASS_FAIL_PATTERN.matcher(body).replaceAll("").replaceAll("<PASS/FAIL:>", "").replaceAll("<PASS/FAIL>", "").replaceAll("<PASS>", "").replaceAll("<FAIL>", "").trim();

			return new StructuralAnalysisEntry(last, cleaned);
		}
	}

}
