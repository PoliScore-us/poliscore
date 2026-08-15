package us.poliscore.images;

import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.net.ssl.SSLContext;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.legislator.Legislator;

/**
 * Fetches images from congress.gov for all the legislators and uploads them to our S3 repository.
 * 
 * TODO : Legislators that do not have photos on congress.gov might have photos on bioguide.congress.gov. For example:
 * https://bioguide.congress.gov/search/bio/L000592
 * 
 * We could (or should?) fetch these from here:
 * https://github.com/unitedstates/images
 */
@QuarkusMain(name="CongressionalLegislatorImageFetcher")
public class CongressionalLegislatorImageFetcher extends AbstractLegislatorImageFetcher implements QuarkusApplication {
	
	// This code was used to patch the 119th session from jpg to webp. We'll keep it around for a minute, in case we want to patch the 118th session or something. But it's really only here in a temporary sense. Once we've fully moved to webp it won't matter anymore.
//	@SneakyThrows
//	protected Optional<byte[]> fetchExistingJpgImage(Legislator leg, PoliscoreDataset dataset) {
//		var httpClient = getHttpClient();
//		
//		String url = "https://poliscore-prod-public.s3.us-east-1.amazonaws.com/" + leg.getId() + ".jpg";
//		val get = new HttpGet(url);
//		
//		HttpResponse resp = httpClient.execute(get);
//		int status = resp.getStatusLine().getStatusCode();
//
//        @Cleanup InputStream is = resp.getEntity().getContent();
//
//        if (status == 429 || status == 403 || status >= 400) {
//        	val body = IOUtils.toString(is, "UTF-8");
//            Log.warn("[" + leg.getCode() + "] Received " + status + " fetching jpg. " + body.substring(0, Math.min(body.length(), 300)));
//            return Optional.empty();
//        }
//
//        byte[] image = IOUtils.toByteArray(is);
//        if (!isJPEG(image)) {
//            Log.warn("[" + leg.getCode() + "] S3 returend invalid image data?");
//            return Optional.empty();
//        }
//        
//        byte[] webp = convertToWebp(image);
//
//        return Optional.of(webp);
//	}
	
	@SneakyThrows
	@Override
	protected Optional<byte[]> fetchImage(Legislator leg, PoliscoreDatasetIF dataset) {
//		var existingJpg = fetchExistingJpgImage(leg, dataset);
//		if (existingJpg.isPresent()) return existingJpg;
		
	    for (String url : primaryImageUrlCandidates(leg)) {
	    	Optional<byte[]> image = fetchImageFromUrl(leg, url);
	    	if (image.isPresent()) return image;
	    }
	    
	    Optional<byte[]> scrapedImage = fetchImageFromUrl(leg, scrapeImageUrlFromMemberPage(leg));
	    if (scrapedImage.isPresent()) return scrapedImage;
	    
	    Optional<byte[]> fallbackImage = fetchImageFromUrl(leg, congressMemberImageFallback(leg));
	    if (fallbackImage.isPresent()) return fallbackImage;
	    
	    return Optional.empty();
	}
	
	@SneakyThrows
	private Optional<byte[]> fetchImageFromUrl(Legislator leg, String url) {
		if (url == null || url.isBlank()) return Optional.empty();

	    final int MAX_RETRIES = 5;
	    int attempt = 0;
	    int backoffMs = 2000;

	    while (attempt < MAX_RETRIES) {
	        attempt++;

	        var httpClient = getHttpClient();

	        val get = new HttpGet(url);
	        get.addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:126.0) Gecko/20100101 Firefox/126.0");
	        get.addHeader("Accept", "image/avif,image/webp,*/*");
	        get.addHeader("Sec-Fetch-Dest", "image");

	        HttpResponse resp = httpClient.execute(get);
	        int status = resp.getStatusLine().getStatusCode();

	        @Cleanup InputStream is = resp.getEntity().getContent();

	        if (status == 429 || status == 403) {
	            Log.warn("[" + leg.getCode() + "] Received " + status + " fetching image url [" + url + "]. Waiting " + backoffMs + "ms...");
	            Thread.sleep(backoffMs);
	            backoffMs = Math.min(backoffMs * 2, 60000); // max 1 minute
	            continue;
	        }

	        if (status >= 400) {
	            val body = IOUtils.toString(is, "UTF-8");
	            Log.warn("[" + leg.getCode() + "] HTTP " + status + " fetching image url [" + url + "]: " + body.substring(0, Math.min(body.length(), 300)));
	            return Optional.empty(); // don't retry this URL; the caller may try another source.
	        }

	        byte[] image = IOUtils.toByteArray(is);
	        if (!isValidImage(image)) {
	            Log.warn("[" + leg.getCode() + "] Invalid image data from url [" + url + "]. Waiting " + backoffMs + "ms...");
	            Thread.sleep(backoffMs);
	            backoffMs = Math.min(backoffMs * 2, 60000);
	            continue;
	        }
	        
	        byte[] webp = convertToWebp(image);

	        return Optional.of(webp);
	    }

	    Log.warn("[" + leg.getCode() + "] Exceeded retry limit.");
	    return Optional.empty();
	}
	
	static List<String> primaryImageUrlCandidates(Legislator leg) {
		val urls = new ArrayList<String>();
		
		urls.add("https://clerk.house.gov/images/members/" + leg.getCode() + ".jpg");
		urls.add("https://raw.githubusercontent.com/unitedstates/images/gh-pages/congress/450x550/" + leg.getCode() + ".jpg");
		urls.add("https://raw.githubusercontent.com/unitedstates/images/gh-pages/congress/original/" + leg.getCode() + ".jpg");
		
		return urls.stream().distinct().toList();
	}
	
	static String congressMemberImageFallback(Legislator leg) {
		return "https://www.congress.gov/img/member/" + leg.getCode().toLowerCase() + "_200.jpg";
	}
	
	/**
	 * The legislator images on congress.gov do not follow a consistent pattern. The most consistent pattern seems to be something like: 
	 * 		https://www.congress.gov/img/member/" + leg.getCode().toLowerCase() + "_200.jpg
	 * 
	 * And this actually works for about 90% or 95% of legislators. The rest of the legislators follow inconsistent naming conventions,
	 * for example John Peterson (P000263)'s image url is /img/member/h_peterson_john_20073196577_200.jpg.
	 * 
	 * This algorithm's job is to fetch the legislator's member page (at congress.gov/member), and then find the photo url on that page
	 * and then return that url.
	 * 
	 * @param leg
	 * @return
	 */
	@SneakyThrows
	static String scrapeImageUrlFromMemberPage(Legislator leg) {
	    val memberUrl = "https://www.congress.gov/member/"
	        + leg.getName().getFirst().toLowerCase().replace(" ", "-")
	        + "-"
	        + leg.getName().getLast().toLowerCase().replace(" ", "-")
	        + "/"
	        + leg.getCode();

	    // Reuse the exact SSL setup as before
	    KeyStore keyStore = KeyStore.getInstance("PKCS12");
	    keyStore.load(CongressionalLegislatorImageFetcher.class.getResourceAsStream("keystore"), "changeit".toCharArray());

	    SSLContext sslContext = SSLContexts.custom()
	        .loadKeyMaterial(keyStore, null)
	        .build();

	    CloseableHttpClient httpClient = HttpClients.custom()
	        .setSSLContext(sslContext)
	        .build();

	    val get = new HttpGet(memberUrl);
	    get.addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:126.0) Gecko/20100101 Firefox/126.0");
	    get.addHeader("Accept", "text/html");

	    HttpResponse resp = httpClient.execute(get);
	    int status = resp.getStatusLine().getStatusCode();
	    @Cleanup InputStream is = resp.getEntity().getContent();

	    if (status >= 400) {
	        Log.warn("[" + leg.getCode() + "] Failed to fetch member page: HTTP " + status);
	        return null;
	    }

	    val html = IOUtils.toString(is, "UTF-8");
	    val doc = Jsoup.parse(html, memberUrl);
	    val photo = doc.selectFirst("a[href*='bioguide.congress.gov/photo/'], img[src*='bioguide.congress.gov/photo/'], .overview-member-column-picture img");

	    return imageUrlFromElement(photo);
	}
	
	static String imageUrlFromElement(Element el) {
		if (el == null) return null;
		
		val attr = el.hasAttr("href") ? "href" : "src";
		val url = el.absUrl(attr);
		
		if (url == null || url.isBlank()) return null;
		
		return url;
	}
	
	public static void main(String[] args) {
		Quarkus.run(CongressionalLegislatorImageFetcher.class, args);
	}
	
}
