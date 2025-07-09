package us.poliscore.images;

import java.io.InputStream;
import java.security.KeyStore;
import java.util.Optional;

import javax.net.ssl.SSLContext;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.jsoup.Jsoup;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreDataset;
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
	
	@SneakyThrows
	@Override
	protected Optional<byte[]> fetchImage(Legislator leg, PoliscoreDataset dataset) {
	    String url = scrapeImageUrlFromMemberPage(leg);

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
	            Log.warn("[" + leg.getBioguideId() + "] Received " + status + " (rate limit). Waiting " + backoffMs + "ms...");
	            Thread.sleep(backoffMs);
	            backoffMs = Math.min(backoffMs * 2, 60000); // max 1 minute
	            continue;
	        }

	        if (status >= 400) {
	            val body = IOUtils.toString(is, "UTF-8");
	            Log.warn("[" + leg.getBioguideId() + "] HTTP " + status + ": " + body.substring(0, Math.min(body.length(), 300)));
	            return Optional.empty(); // don't retry 404s, 500s, etc.
	        }

	        byte[] image = IOUtils.toByteArray(is);
	        if (!isJPEG(image)) {
	            Log.warn("[" + leg.getBioguideId() + "] Invalid image data. Waiting " + backoffMs + "ms...");
	            Thread.sleep(backoffMs);
	            backoffMs = Math.min(backoffMs * 2, 60000);
	            continue;
	        }
	        
	        byte[] webp = convertToWebp(image);

	        return Optional.of(webp);
	    }

	    Log.warn("[" + leg.getBioguideId() + "] Exceeded retry limit.");
	    return Optional.empty();
	}
	
	/**
	 * The legislator images on congress.gov do not follow a consistent pattern. The most consistent pattern seems to be something like: 
	 * 		https://www.congress.gov/img/member/" + leg.getBioguideId().toLowerCase() + "_200.jpg
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
	private String scrapeImageUrlFromMemberPage(Legislator leg) {
	    val fallback = "https://www.congress.gov/img/member/" + leg.getBioguideId().toLowerCase() + "_200.jpg";

	    val memberUrl = "https://www.congress.gov/member/"
	        + leg.getName().getFirst().toLowerCase().replace(" ", "-")
	        + "-"
	        + leg.getName().getLast().toLowerCase().replace(" ", "-")
	        + "/"
	        + leg.getBioguideId();

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
	        Log.warn("[" + leg.getBioguideId() + "] Failed to fetch member page: HTTP " + status);
	        return fallback;
	    }

	    val html = IOUtils.toString(is, "UTF-8");
	    val img = Jsoup.parse(html).selectFirst(".overview-member-column-picture > img");

	    if (img == null) {
	        return fallback;
	    } else {
	        return "https://www.congress.gov" + img.attr("src");
	    }
	}
	
	public static void main(String[] args) {
		Quarkus.run(CongressionalLegislatorImageFetcher.class, args);
	}
	
}
