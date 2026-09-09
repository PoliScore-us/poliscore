package us.poliscore.images;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StateLegislatorImageFetcherTest {

	@Test
	void acceptsAbsoluteHttpImageUrls() {
		assertTrue(StateLegislatorImageFetcher.isAbsoluteHttpUrl("https://www.legis.ga.gov/image.jpg"));
		assertTrue(StateLegislatorImageFetcher.isAbsoluteHttpUrl("http://example.test/image.png"));
	}

	@Test
	void rejectsBlankRelativeAndNonHttpUrls() {
		assertFalse(StateLegislatorImageFetcher.isAbsoluteHttpUrl(null));
		assertFalse(StateLegislatorImageFetcher.isAbsoluteHttpUrl(""));
		assertFalse(StateLegislatorImageFetcher.isAbsoluteHttpUrl("/members/house/806"));
		assertFalse(StateLegislatorImageFetcher.isAbsoluteHttpUrl("file:///tmp/image.jpg"));
	}
}
