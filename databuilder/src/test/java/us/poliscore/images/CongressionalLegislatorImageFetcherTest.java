package us.poliscore.images;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import us.poliscore.model.legislator.Legislator;

class CongressionalLegislatorImageFetcherTest {

	@Test
	void shouldPreferUnitedStatesImagesByBioguideCode() {
		var leg = ashleyMoody();
		
		assertEquals(
				"https://raw.githubusercontent.com/unitedstates/images/gh-pages/congress/450x550/M001244.jpg",
				CongressionalLegislatorImageFetcher.primaryImageUrlCandidates(leg).get(0));
		assertEquals(
				"https://raw.githubusercontent.com/unitedstates/images/gh-pages/congress/original/M001244.jpg",
				CongressionalLegislatorImageFetcher.primaryImageUrlCandidates(leg).get(1));
	}
	
	@Test
	void shouldPreserveOldCongressMemberImageFallback() {
		assertEquals(
				"https://www.congress.gov/img/member/m001244_200.jpg",
				CongressionalLegislatorImageFetcher.congressMemberImageFallback(ashleyMoody()));
	}
	
	@Test
	void shouldReadAbsoluteBioguidePhotoLinksFromCongressMarkup() {
		var doc = Jsoup.parse(
				"<a href=\"https://bioguide.congress.gov/photo/695d82c8550dfb80c3063bee.jpg\">Official U.S. Senate Photo</a>",
				"https://www.congress.gov/member/ashley-moody/M001244");
		
		assertEquals(
				"https://bioguide.congress.gov/photo/695d82c8550dfb80c3063bee.jpg",
				CongressionalLegislatorImageFetcher.imageUrlFromElement(doc.selectFirst("a")));
	}
	
	@Test
	void shouldResolveRelativeCongressImageUrls() {
		var doc = Jsoup.parse(
				"<img src=\"/img/member/m001244_200.jpg\">",
				"https://www.congress.gov/member/ashley-moody/M001244");
		
		assertEquals(
				"https://www.congress.gov/img/member/m001244_200.jpg",
				CongressionalLegislatorImageFetcher.imageUrlFromElement(doc.selectFirst("img")));
	}
	
	private Legislator ashleyMoody() {
		var leg = new Legislator();
		leg.setId("LEG/us/congress/119/M001244");
		leg.setName(new Legislator.LegislatorName("Ashley", "Moody", "Ashley Moody"));
		return leg;
	}
}
