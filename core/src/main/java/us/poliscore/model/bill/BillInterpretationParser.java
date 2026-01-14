package us.poliscore.model.bill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
	
	private int searchReferenceCount = 0;

	public static enum State {
		REASONING("(?i)Reasoning Steps:"), STRUCTURAL("(?i)Structural Analysis:"),
		NEUTRAL_SUMMARY("(?i)Neutral Summary:"),
		SEARCH_REFERENCES("(?i)Search References:"), IMPACT_ANALYSIS("(?i)Impact Analysis:"), IMPACT("(?i)Impact Stats:"), RATING("(?i)Rating:"),
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
		interp.setImpactAnalysis("");
		interp.setShortExplain("");
		interp.setLongExplain("");
		interp.setNeutralSummary("");
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

		cleanIssueStats(interp.getIssueStats());

		// Parse structural analysis into clean outputs
        StructuralAnalysisParser.StructuralAnalysisParsed saParsed = StructuralAnalysisParser.parse(interp.getStructuralAnalysisRaw());
        interp.setStructuralAnalysisPassFail(saParsed.getResults());
        interp.setStructuralAnalysisExplain(saParsed.getAnalyses());
        
        interp.validate();
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
		} else if (State.NEUTRAL_SUMMARY.equals(state)) {
			processNeutralSummary(line);
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
		} else if (State.IMPACT_ANALYSIS.equals(state)) {
			processImpactAnalysis(line);
		}
	}

	private void cleanIssueStats(IssueStats stats) {
//		int zeroCount = 0;
//		int totalSet = 0;
//		for (TrackedIssue issue : TrackedIssue.values()) {
//			if (issue != TrackedIssue.OverallBenefitToSociety && stats.hasStat(issue)) {
//				totalSet++;
//				if (stats.getStat(issue) == 0)
//					zeroCount++;
//			}
//		}

//		if (Math.abs(totalSet - TrackedIssue.values().length) <= 2 && zeroCount > 1) {
//			logger.error("Malformed AI response for bill [" + this.interp.billId
//					+ "]: too many tracked issues were assigned a value of 0. Only include an issue if it is truly relevant. Zeros will be removed from issue stats.");

			for (TrackedIssue issue : TrackedIssue.values()) {
				if (stats.hasStat(issue) && stats.getStat(issue) == 0
						&& issue != TrackedIssue.OverallBenefitToSociety) {
					stats.removeStat(issue);
				}
			}
//		}
	}

	private void clean(String dirty) {
//		val summaryHeaders = new String[] { "summary of the predicted impact to society and why", "summary of the predicted impact to society", "summary of the bill and predicted impact to society and why", "summary of the bill and predicted impact to society", "summary of the bill and its predicted impact to society and why", "summary of the bill and its predicted impact to society", "Summary of the bill's predicted impact to society and why", "Summary of the bill's predicted impact to society", "summary of predicted impact to society and why", "summary of predicted impact to society", "summary of the impact to society", "summary of impact to society", "summary report", "summary of the impact", "summary of impact", "summary", "explanation" };
//		val summaryHeaderRegex = " *#*\\** *(" + String.join("|", summaryHeaders) + ") *#*\\** *:? *#*\\** *";
//		if (stats.explanation.matches("(?i)^" + summaryHeaderRegex + ".*$")) {
//			stats.explanation = stats.explanation.replaceFirst("(?i)" + summaryHeaderRegex, "");
//		}
	}
	
	private void processImpactAnalysis(String line) {
		String newline = StringUtils.isBlank(interp.getImpactAnalysis()) ? "" : "\n";
		interp.setImpactAnalysis(interp.getImpactAnalysis() + newline + line);
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
		searchReferenceCount++;
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

		if (normalized.contains("supportive") || normalized.contains("positive") || normalized.contains("endorse") || normalized.contains("for"))
			return 75;
		if (normalized.contains("strongly supportive") || normalized.contains("enthusiastic"))
			return 100;

		if (normalized.contains("critical") || normalized.contains("negative") || normalized.contains("against"))
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
	
	private void processNeutralSummary(String line) {
		interp.setNeutralSummary(interp.getNeutralSummary() + "\n" + line);
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

	public static class StructuralAnalysisParser {

	    // <PASS> / <FAIL> anywhere (case-insensitive, whitespace-tolerant)
	    private static final Pattern PASS_FAIL_PATTERN =
	            Pattern.compile("(?i)<\\s*(PASS|FAIL)\\s*>");

	    // Strips ANSI color codes like \u001B[39m
	    private static final Pattern ANSI_PATTERN =
	            Pattern.compile("\\u001B\\[[;\\d]*m");

	    private StructuralAnalysisParser() {}

	    public static class StructuralAnalysisParsed {
	        private final Map<StructuralAnalysis, Boolean> results;
	        private final Map<StructuralAnalysis, String> analyses;

	        public StructuralAnalysisParsed(Map<StructuralAnalysis, Boolean> results,
	                                        Map<StructuralAnalysis, String> analyses) {
	            this.results = results;
	            this.analyses = analyses;
	        }

	        public Map<StructuralAnalysis, Boolean> getResults() { return results; }
	        public Map<StructuralAnalysis, String> getAnalyses() { return analyses; }
	    }

	    public static StructuralAnalysisParsed parse(String structuralAnalysisText) {
	        if (StringUtils.isBlank(structuralAnalysisText)) {
	            throw new RuntimeException("Structural analysis text was blank");
	        }

	        String text = structuralAnalysisText.replace("\r\n", "\n");
	        text = ANSI_PATTERN.matcher(text).replaceAll("");

	        // Accumulate raw bodies per pillar in encounter order
	        Map<StructuralAnalysis, StringBuilder> bodies = new LinkedHashMap<>();

	        StructuralAnalysis current = null;

	        try (Scanner scanner = new Scanner(text)) {
	            while (scanner.hasNextLine()) {
	                String rawLine = scanner.nextLine();
	                String line = rawLine.trim();

	                if (StringUtils.isBlank(line)) {
	                    // preserve blank lines inside bodies (optional); easiest: ignore them
	                    // If you want them, append "\n" when current != null.
	                    continue;
	                }

	                // If this line starts a new pillar header, switch state
	                HeaderMatch hm = matchHeader(line);
	                if (hm != null) {
	                    current = hm.pillar;
	                    bodies.computeIfAbsent(current, k -> new StringBuilder());

	                    // Inline analysis (same line after header) should be captured too
	                    if (StringUtils.isNotBlank(hm.inlineBody)) {
	                        appendLine(bodies.get(current), hm.inlineBody);
	                    }

	                    continue;
	                }

	                // Otherwise it's body content for the current pillar (if any)
	                if (current != null) {
	                    appendLine(bodies.get(current), line);
	                }
	            }
	        }

	        // Now produce outputs
	        Map<StructuralAnalysis, Boolean> results = new HashMap<>();
	        Map<StructuralAnalysis, String> analyses = new HashMap<>();

	        // Must see every enum at least once
	        for (StructuralAnalysis sa : StructuralAnalysis.values()) {
	            StringBuilder sb = bodies.get(sa);
	            if (sb == null) {
	                throw new RuntimeException("Missing structural analysis pillar: " + sa);
	            }

	            String body = sb.toString().trim();
	            if (StringUtils.isBlank(body)) {
	                throw new RuntimeException("Empty analysis body for pillar: " + sa);
	            }

	            Boolean passFail = findLastPassFail(body);
	            if (passFail == null) {
	                throw new RuntimeException("Missing <PASS> or <FAIL> tag for pillar: " + sa);
	            }

	            String cleaned = cleanBody(body);
	            if (StringUtils.isBlank(cleaned)) {
	                // if you want to allow “tag only” pillars, remove this check
	                throw new RuntimeException("Analysis text was empty after cleaning for pillar: " + sa);
	            }

	            results.put(sa, passFail);
	            analyses.put(sa, cleaned);
	        }

	        // Defensive: ensure we populated all
	        if (results.size() != StructuralAnalysis.values().length ||
	            analyses.size() != StructuralAnalysis.values().length) {
	            throw new RuntimeException("Unable to parse all structural analysis pillars from input text: " + structuralAnalysisText);
	        }

	        return new StructuralAnalysisParsed(results, analyses);
	    }

	    private static void appendLine(StringBuilder sb, String line) {
	        if (sb.length() > 0) sb.append("\n");
	        sb.append(line);
	    }

	    private static Boolean findLastPassFail(String body) {
	        Matcher m = PASS_FAIL_PATTERN.matcher(body);
	        Boolean last = null;
	        while (m.find()) {
	            String token = m.group(1).toUpperCase(Locale.ROOT);
	            last = "PASS".equals(token) ? Boolean.TRUE : Boolean.FALSE;
	        }
	        return last;
	    }

	    private static String cleanBody(String body) {
	        // Remove all PASS/FAIL tags
	        String cleaned = PASS_FAIL_PATTERN.matcher(body).replaceAll("");

	        // Remove your occasional variants
	        cleaned = cleaned
	                .replace("<PASS/FAIL:>", "")
	                .replace("<PASS/FAIL>", "");

	        return cleaned.trim();
	    }

	    private static class HeaderMatch {
	        final StructuralAnalysis pillar;
	        final String inlineBody;

	        HeaderMatch(StructuralAnalysis pillar, String inlineBody) {
	            this.pillar = pillar;
	            this.inlineBody = inlineBody;
	        }
	    }

	    /**
	     * Enum-driven structural analysis header matcher.
	     *
	     * A header is recognized ONLY if it begins with the enum’s numeric order
	     * followed by an optional "." or ")" delimiter, optional whitespace, the
	     * enum display name, and a required ":" delimiter.
	     *
	     * Accepted examples:
	     *  - "1. Precision:"
	     *  - "1 Precision:"
	     *  - "1)Precision:"
	     *  - "1)   **Precision**: inline analysis text"
	     *
	     * Rejected examples:
	     *  - "Precision:"              (missing numeric prefix)
	     *  - "**Precision**:"          (missing numeric prefix)
	     *  - "1 - Precision:"          (unsupported delimiter)
	     *
	     * Notes:
	     *  - Matching is case-insensitive.
	     *  - Simple markdown emphasis around the display name is tolerated.
	     *  - Any text following the ":" is captured as inline body content.
	     */
	    private static HeaderMatch matchHeader(String line) {
	        String working = ANSI_PATTERN.matcher(line).replaceAll("").trim();

	        for (StructuralAnalysis sa : StructuralAnalysis.values()) {
	            int n = sa.getNumber();
	            String dn = sa.getDisplayName();

	            /*
	             * Accepts:
	             *  "1. Precision:"
	             *  "1 Precision:"
	             *  "1)Precision:"
	             *  "1)   **Precision**: blah"
	             * Rejects:
	             *  "Precision:" (no number)
	             */
	            String regex =
	                    "^\\s*"
	                  + n
	                  + "(?:[\\.)])?"     // optional "." or ")"
	                  + "\\s*"            // optional whitespace (incl none)
	                  + "(?:\\*\\*|__|\\*|_)?\\s*"
	                  + Pattern.quote(dn)
	                  + "\\s*(?:\\*\\*|__|\\*|_)?"
	                  + "\\s*:\\s*(.*)$";

	            Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(working);
	            if (m.matches()) {
	                String inline = m.group(1);
	                inline = (inline == null) ? null : inline.trim();
	                return new HeaderMatch(sa, StringUtils.isBlank(inline) ? null : inline);
	            }
	        }

	        return null;
	    }
	}

}
