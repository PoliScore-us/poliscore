package us.poliscore.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.apache.tika.exception.TikaException;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

class PDFToTextTest {

	@Test
	void ingestionPreservesTikaOutputIncludingOfficialLineNumbersAndWhitespace() throws Exception {
		String extracted = "  1 First official line  \n\n  2 Second official line\n";
		PDFToText parser = new PDFToText() {
			@Override
			String extractWithTika(byte[] pdfBytes) throws IOException, TikaException, SAXException {
				return extracted;
			}
		};

		assertEquals(extracted, parser.extract(new byte[] { 1 }));
	}
}
