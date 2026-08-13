package us.poliscore.model.legislator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.LegislativeNamespace;

class LegislatorMediaReferenceTest {

	@Test
	void generatesStableIdsFromCanonicalArticleUrl() {
		String legislatorId = "LEG/us/congress/119/B001230";
		String first = LegislatorMediaReference.generateId(legislatorId,
				new InterpretationOrigin("https://Example.com:443/news/story/#coverage", "Example News"));
		String sameArticle = LegislatorMediaReference.generateId(legislatorId,
				new InterpretationOrigin("https://example.com/news/story", "Renamed Publisher"));
		String differentArticle = LegislatorMediaReference.generateId(legislatorId,
				new InterpretationOrigin("https://example.com/news/other", "Example News"));

		assertEquals(first, sameArticle);
		assertNotEquals(first, differentArticle);
	}

	@Test
	void parsesRichMediaReferenceJson() {
		LegislatorInterpretation interpretation = new LegislatorInterpretation(
				LegislativeNamespace.US_CONGRESS,
				"119",
				"B001230",
				AIInterpretationMetadata.construct("test", "test", 1, true),
				null);
		interpretation.setLastUpdate(LocalDateTime.of(2026, 3, 1, 0, 0));

		LegislatorInterpretationParser parser = new LegislatorInterpretationParser(interpretation);
		parser.parse("""
				Short Report:
				Focuses on testing.

				Media References:
				```json
				[
				  {
				    "type": "MISCONDUCT",
				    "mediaOrganization": "Example News",
				    "articleTitle": "A reported incident",
				    "author": "A. Reporter",
				    "publishedDate": "2026/08/01",
				    "url": "https://example.com/news/incident",
				    "quickSummary": "A short summary.",
				    "longSummary": "A longer factual summary.",
				    "sentiment": -140,
				    "sentimentText": "Negative",
				    "trustworthiness": 87,
				    "confidence": 92
				  }
				]
				```
				""");

		LegislatorMediaReference reference = parser.getMediaReferences().getFirst();
		assertEquals("LEG/us/congress/119/B001230", reference.getLegislatorId());
		assertEquals("Example News", reference.getOrigin().getTitle());
		assertEquals("A reported incident", reference.getGenArticleTitle());
		assertEquals("A short summary.", reference.getShortExplain());
		assertEquals("A longer factual summary.", reference.getLongExplain());
		assertEquals(-100, reference.getSentiment());
		assertEquals(87, reference.getTrustworthiness());
		assertEquals(92, reference.getConfidence());
	}
}
