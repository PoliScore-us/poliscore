package us.poliscore.parsing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

public class PDFToText {

	private static final Pattern COLORADO_PAGE_FOOTER = Pattern.compile("(?m)^\\s*(?:-\\d+-\\s+\\d+|\\d+-\\d+-)\\s*$");
	private static final Pattern LEADING_LINE_NUMBER = Pattern.compile("^\\s*([1-9]\\d{0,5})[ \\t]+(\\S.*)$");
	private static final Pattern STANDALONE_LINE_NUMBER = Pattern.compile("^\\s*([1-9]\\d{0,5})\\s*$");
	// A run distinguishes printed line numbers from isolated numeric prose or list items.
	private static final int MINIMUM_SEQUENTIAL_LINE_NUMBERS = 5;

	public String extract(byte[] pdfBytes) throws IOException, TikaException, SAXException {
		if (pdfBytes == null || pdfBytes.length == 0) {
			return "";
		}

		String text = extractWithTika(pdfBytes);
		return cleanLegislativePdfText(text);
	}

	private String extractWithTika(byte[] pdfBytes) throws IOException, TikaException, SAXException {
		PDFParserConfig config = new PDFParserConfig();
		config.setSortByPosition(true);

		ParseContext context = new ParseContext();
		context.set(PDFParserConfig.class, config);

		BodyContentHandler handler = new BodyContentHandler(-1);
		Metadata metadata = new Metadata();

		new PDFParser().parse(new ByteArrayInputStream(pdfBytes), handler, metadata, context);
		return handler.toString();
	}

	static String cleanLegislativePdfText(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}

		String cleaned = COLORADO_PAGE_FOOTER.matcher(text).replaceAll("");
		cleaned = stripSequentialLineNumbers(cleaned);
		return cleaned.replaceAll("(?m)[ \\t]+$", "")
				.replaceAll("\\n{3,}", "\n\n")
				.strip();
	}

	private static String stripSequentialLineNumbers(String text) {
		String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		List<NumberedLine> numberedLines = new ArrayList<>();
		boolean[] strip = new boolean[lines.length];

		for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
			NumberedLine numberedLine = numberedLine(lineIndex, lines[lineIndex]);
			if (numberedLine == null) {
				if (lines[lineIndex].isBlank()) {
					continue;
				}
				markSequentialRun(numberedLines, strip);
				numberedLines.clear();
				continue;
			}

			if (!numberedLines.isEmpty()
					&& numberedLine.number() != numberedLines.getLast().number() + 1) {
				markSequentialRun(numberedLines, strip);
				numberedLines.clear();
			}
			numberedLines.add(numberedLine);
		}
		markSequentialRun(numberedLines, strip);

		for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
			if (!strip[lineIndex]) {
				continue;
			}

			Matcher leading = LEADING_LINE_NUMBER.matcher(lines[lineIndex]);
			lines[lineIndex] = leading.matches() ? leading.group(2) : "";
		}

		return String.join("\n", lines);
	}

	private static NumberedLine numberedLine(int lineIndex, String line) {
		Matcher leading = LEADING_LINE_NUMBER.matcher(line);
		if (leading.matches()) {
			return new NumberedLine(lineIndex, Integer.parseInt(leading.group(1)));
		}

		Matcher standalone = STANDALONE_LINE_NUMBER.matcher(line);
		return standalone.matches()
				? new NumberedLine(lineIndex, Integer.parseInt(standalone.group(1)))
				: null;
	}

	private static void markSequentialRun(List<NumberedLine> numberedLines, boolean[] strip) {
		if (numberedLines.size() < MINIMUM_SEQUENTIAL_LINE_NUMBERS) {
			return;
		}

		for (NumberedLine numberedLine : numberedLines) {
			strip[numberedLine.lineIndex()] = true;
		}
	}

	private record NumberedLine(int lineIndex, int number) { }
}
