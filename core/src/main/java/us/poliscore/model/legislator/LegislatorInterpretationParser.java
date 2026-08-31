package us.poliscore.model.legislator;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import us.poliscore.model.InterpretationOrigin;

public class LegislatorInterpretationParser {

	private static Logger logger = LoggerFactory.getLogger(LegislatorInterpretationParser.class);
	private static final ObjectMapper objectMapper = new ObjectMapper();
	
	private State state = null;
	private LegislatorInterpretation interp;
	private final List<LegislatorMediaReference> mediaReferences = new ArrayList<>();

	public static enum State {
		REASONING(markdownHeader("Reasoning Steps")),
		SHORT_REPORT(markdownHeader("Short Report")),
		CASUAL_REPORT(markdownHeader("Casual Report")),
		LONG_REPORT(markdownHeader("Long Report")),
		CONSTITUENCY(markdownHeader("Constituency")),
		CAMPAIGN_FINANCE(markdownHeader("Campaign Finance")),
		REFERENCES(markdownHeader("References"), markdownHeader("Media References"));

		private List<String> regex;

		private State(String... regex) {
			this.regex = Arrays.asList(regex);
		}
	}

	private static String markdownHeader(String title) {
		return "(?i)(?:#{1,6}\\s+)?(?:\\*\\*|__)?" + title
				+ "(?:\\*\\*|__)?\\s*:(?:\\*\\*|__)?";
	}

	public LegislatorInterpretationParser(LegislatorInterpretation interp) {
		this.interp = interp;
	}

	public void parse(String text) {
		state = null;
		mediaReferences.clear();
		interp.setShortExplain("");
		interp.setCasualExplain("");
		interp.setLongExplain("");
		interp.setReasoning("");
		interp.setReferences("");
		interp.setConstituency("");
		interp.setCampaignFinance("");

		try (final Scanner scanner = new Scanner(text)) {
			while (scanner.hasNextLine()) {
				String line = scanner.nextLine().strip();

				if (StringUtils.isBlank(line) || setState(line) || state == null)
					continue;

				processContent(line);
			}
		}
		
		interp.setShortExplain(stripMultiLines(interp.getShortExplain()));
		interp.setCasualExplain(stripMultiLines(interp.getCasualExplain()));
		interp.setLongExplain(stripMultiLines(interp.getLongExplain()));
		interp.setConstituency(parseJsonObject(interp.getConstituency(), "constituency"));
		interp.setCampaignFinance(parseJsonObject(interp.getCampaignFinance(), "campaign finance"));
		parseMediaReferences();
		
		interp.validate();
	}

	public List<LegislatorMediaReference> getMediaReferences() {
		return List.copyOf(mediaReferences);
	}
	
	public static String stripMultiLines(String shortExplain) {
	    String text = shortExplain;
	    if (text == null || text.isBlank()) {
	        return null;
	    }

	    // Normalize newlines so we can handle Windows/Mac/Linux consistently
	    text = text.replace("\r\n", "\n").replace('\r', '\n');

	    // Split preserving trailing empty lines (we want to be able to trim them explicitly)
	    String[] lines = text.split("\n", -1);

	    // 1) Trim trailing whitespace on every line
	    for (int i = 0; i < lines.length; i++) {
	        lines[i] = lines[i].replaceAll("[ \\t\\f\\u000B]+$", "");
	    }

	    // 2) Remove leading/trailing "empty" lines where "empty" includes:
	    //    - blank lines
	    //    - lines that are exactly "--" (after the trailing-whitespace trim above)
	    int start = 0;
	    while (start < lines.length && (lines[start].isBlank() || lines[start].equals("-") || lines[start].equals("--") || lines[start].equals("---"))) {
	        start++;
	    }

	    int end = lines.length - 1;
	    while (end >= start && (lines[end].isBlank() || lines[end].equals("-") || lines[end].equals("--") || lines[end].equals("---"))) {
	        end--;
	    }

	    // If everything got stripped, set to empty (or null if you prefer)
	    if (start > end) {
	        return "";
	    }

	    // 3) Rebuild, also collapsing consecutive blank lines inside the body to a single blank line
	    StringBuilder sb = new StringBuilder();

	    for (int i = start; i <= end; i++) {
	        String line = lines[i];
	        boolean isBlank = line.isBlank();

	        if (isBlank) {
	            sb.append('\n'); // keep a single blank line
	            continue;
	        }

	        sb.append(line);
	        if (i < end) sb.append('\n');
	    }

	    // If we ended with a newline due to a blank line, trim it off
	    String stripped = sb.toString();
	    while (stripped.endsWith("\n")) {
	        stripped = stripped.substring(0, stripped.length() - 1);
	    }

	    return stripped;
	}

	private void processContent(String line) {
		if (State.SHORT_REPORT.equals(state)) {
			interp.setShortExplain(interp.getShortExplain() + "\n" + line);
		} else if (State.LONG_REPORT.equals(state)) {
			interp.setLongExplain(interp.getLongExplain() + "\n" + line);
		} else if (State.CASUAL_REPORT.equals(state)) {
			interp.setCasualExplain(interp.getCasualExplain() + "\n" + line);
		} else if (State.REASONING.equals(state)) {
			interp.setReasoning(interp.getReasoning() + "\n" + line);
		} else if (State.CONSTITUENCY.equals(state)) {
			interp.setConstituency(interp.getConstituency() + "\n" + line);
		} else if (State.CAMPAIGN_FINANCE.equals(state)) {
			interp.setCampaignFinance(interp.getCampaignFinance() + "\n" + line);
		} else if (State.REFERENCES.equals(state)) {
			processReferences(line);
		}
	}

	private String parseJsonObject(String raw, String sectionName) {
		if (StringUtils.isBlank(raw)) return null;

		int start = raw.indexOf('{');
		int end = raw.lastIndexOf('}');
		if (start < 0 || end < start) {
			logger.warn("Unable to find JSON object in {} section", sectionName);
			return null;
		}

		try {
			JsonNode root = objectMapper.readTree(raw.substring(start, end + 1));
			if (!root.isObject()) {
				logger.warn("Ignoring non-object JSON in {} section", sectionName);
				return null;
			}
			return root.toString();
		} catch (Exception invalidJson) {
			logger.error("Error parsing {} JSON", sectionName, invalidJson);
			return null;
		}
	}
	
	private void processReferences(String line) {
		try {
			interp.setReferences(interp.getReferences() + "\n" + line);
		} catch (Throwable t) {
			logger.error("Error encountered processing references", t);
		}
	}

	private void parseMediaReferences() {
		String raw = interp.getReferences();
		if (StringUtils.isBlank(raw)) return;

		int start = raw.indexOf('[');
		int end = raw.lastIndexOf(']');
		if (start < 0 || end < start) return;

		try {
			JsonNode root = objectMapper.readTree(raw.substring(start, end + 1));
			if (!root.isArray()) return;

			for (JsonNode value : root) {
				try {
					String url = text(value, "url");
					String organization = firstText(value, "mediaOrganization", "source");
					if (StringUtils.isBlank(url) || StringUtils.isBlank(organization)) continue;

					URI parsedUrl = URI.create(url);
					if (!parsedUrl.isAbsolute() || parsedUrl.getHost() == null
							|| !("http".equalsIgnoreCase(parsedUrl.getScheme()) || "https".equalsIgnoreCase(parsedUrl.getScheme()))) continue;

					InterpretationOrigin origin = new InterpretationOrigin(url, organization);
					LegislatorMediaReference reference = new LegislatorMediaReference();
					reference.setLegislatorId(interp.getLegislatorId());
					reference.setOrigin(origin);
					reference.setId(LegislatorMediaReference.generateId(interp.getLegislatorId(), origin));
					reference.setMetadata(interp.getMetadata());
					reference.setGenArticleTitle(firstText(value, "articleTitle", "title"));
					reference.setAuthor(text(value, "author"));
					reference.setPublishedDate(firstText(value, "publishedDate", "date"));
					reference.setType(text(value, "type"));
					reference.setShortExplain(firstText(value, "quickSummary", "description"));
					reference.setLongExplain(firstText(value, "longSummary", "description"));
					reference.setSentiment(clamp(value.path("sentiment").asInt(0), -100, 100));
					reference.setSentimentText(text(value, "sentimentText"));
					reference.setTrustworthiness(clamp(value.path("trustworthiness").asInt(-1), -1, 100));
					reference.setConfidence(clamp(value.path("confidence").asInt(-1), -1, 100));
					reference.setNoInterp(false);
					reference.setLastUpdate(LocalDateTime.now());
					mediaReferences.add(reference);
				} catch (RuntimeException invalidReference) {
					logger.warn("Ignoring invalid legislator media reference", invalidReference);
				}
			}
		} catch (Exception invalidJson) {
			logger.error("Error parsing legislator media references", invalidJson);
		}
	}

	private String firstText(JsonNode value, String... fields) {
		for (String field : fields) {
			String result = text(value, field);
			if (!StringUtils.isBlank(result)) return result;
		}
		return "";
	}

	private String text(JsonNode value, String field) {
		JsonNode fieldValue = value.get(field);
		return fieldValue == null || fieldValue.isNull() ? "" : fieldValue.asText("").strip();
	}

	private int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private boolean setState(String line) {
		for (State s : State.values()) {
			for (String regex : s.regex) {
				if (line.matches(regex + ".*")) {
					state = s;

					// Handle inline content
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
