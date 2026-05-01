package us.poliscore.model.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class TopicCanonicalizerTest {

	@Test
	void mapsManualSynonymsToCanonicalTopics() {
		assertEquals("ranked choice voting", TopicCanonicalizer.canonicalizeTopic("RCV"));
		assertEquals("ranked choice voting", TopicCanonicalizer.canonicalizeTopic("ranked choice voting"));
		assertEquals("ranked choice voting", TopicCanonicalizer.canonicalizeTopic("ranked-choice voting prohibition"));
	}

	@Test
	void collapsesAccessoryNearDuplicatesWithoutCollapsingSpecificTopics() {
		assertEquals(
				List.of("ranked choice voting", "nonprofit transportation", "transportation"),
				TopicCanonicalizer.canonicalizeTopics(List.of(
						"ranked-choice voting",
						"ranked-choice voting ban",
						"nonprofit transportation",
						"transportation")));
	}
}
