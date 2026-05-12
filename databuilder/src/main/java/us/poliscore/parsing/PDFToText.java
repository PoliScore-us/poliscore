package us.poliscore.parsing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
	private static final Pattern COLORADO_LEADING_LINE_NUMBER = Pattern.compile("(?m)^\\s*(?:[1-9]|[12]\\d|10\\d)\\s+");

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

	private String cleanLegislativePdfText(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}

		String cleaned = COLORADO_PAGE_FOOTER.matcher(text).replaceAll("");
		cleaned = COLORADO_LEADING_LINE_NUMBER.matcher(cleaned).replaceAll("");
		return cleaned.replaceAll("(?m)[ \\t]+$", "")
				.replaceAll("\\n{3,}", "\n\n")
				.strip();
	}
}
