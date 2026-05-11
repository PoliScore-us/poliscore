package us.poliscore.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class XMLBillSlicerTest {

	@Test
	void getTitleReadsCongressMetadataTitle() {
		String xml = """
				<bill>
					<metadata>
						<title>Test Bill Title</title>
					</metadata>
					<legis-body>Body text</legis-body>
				</bill>
				""";

		String title = new XMLBillSlicer().getTitle(XMLBillSlicer.toDoc(xml));

		assertEquals("Bill title: Test Bill Title\nSection content:\n", title);
	}

	@Test
	void getTitleReadsPrefixedMetadataTitle() {
		String xml = """
				<bill xmlns:usc="http://example.com/usc">
					<usc:metadata>
						<usc:title>Prefixed Bill Title</usc:title>
					</usc:metadata>
					<usc:legis-body>Body text</usc:legis-body>
				</bill>
				""";

		String title = new XMLBillSlicer().getTitle(XMLBillSlicer.toDoc(xml));

		assertEquals("Bill title: Prefixed Bill Title\nSection content:\n", title);
	}
}
