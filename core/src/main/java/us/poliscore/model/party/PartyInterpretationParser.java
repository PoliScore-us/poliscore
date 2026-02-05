package us.poliscore.model.party;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.session.SessionInterpretation.PartyInterpretation;

public class PartyInterpretationParser {

	private static Logger logger = LoggerFactory.getLogger(PartyInterpretationParser.class);
	
	private State state = null;
	private PartyInterpretation interp;

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

	public PartyInterpretationParser(PartyInterpretation interp) {
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
		
		interp.validate();
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
