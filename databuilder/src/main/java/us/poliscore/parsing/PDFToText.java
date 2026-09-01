package us.poliscore.parsing;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

public class PDFToText {

	public String extract(byte[] pdfBytes) throws IOException, TikaException, SAXException {
		if (pdfBytes == null || pdfBytes.length == 0) {
			return "";
		}

		// Preserve the source representation at ingestion. Consumer-specific cleanup
		// happens later, where removed source features can be returned as metadata.
		return extractWithTika(pdfBytes);
	}

	String extractWithTika(byte[] pdfBytes) throws IOException, TikaException, SAXException {
		PDFParserConfig config = new PDFParserConfig();
		config.setSortByPosition(true);

		ParseContext context = new ParseContext();
		context.set(PDFParserConfig.class, config);

		BodyContentHandler handler = new BodyContentHandler(-1);
		Metadata metadata = new Metadata();

		new PDFParser().parse(new ByteArrayInputStream(pdfBytes), handler, metadata, context);
		return handler.toString();
	}

}
