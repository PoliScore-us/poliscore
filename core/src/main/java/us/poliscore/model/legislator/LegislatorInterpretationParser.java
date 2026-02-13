package us.poliscore.model.legislator;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LegislatorInterpretationParser {

	private static Logger logger = LoggerFactory.getLogger(LegislatorInterpretationParser.class);
	
	private State state = null;
	private LegislatorInterpretation interp;

	public static enum State {
		REASONING("(?i)Reasoning Steps:"),
		SHORT_REPORT("(?i)Short Report:"),
		CASUAL_REPORT("(?i)Casual Report:"),
		LONG_REPORT("(?i)Long Report:"),
		REFERENCES("(?i)References:");

		private List<String> regex;

		private State(String... regex) {
			this.regex = Arrays.asList(regex);
		}
	}

	public LegislatorInterpretationParser(LegislatorInterpretation interp) {
		this.interp = interp;
	}

	public void parse(String text) {
		interp.setShortExplain("");
		interp.setCasualExplain("");
		interp.setLongExplain("");
		interp.setReasoning("");
		interp.setReferences("");

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
		
		interp.validate();
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
		} else if (State.REFERENCES.equals(state)) {
			processReferences(line);
		}
	}
	
	private void processReferences(String line) {
		try {
			interp.setReferences(interp.getReferences() + "\n" + line);
		} catch (Throwable t) {
			logger.error("Error encountered processing references", t);
		}
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
