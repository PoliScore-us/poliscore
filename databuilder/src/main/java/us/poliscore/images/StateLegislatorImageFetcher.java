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
import org.jsoup.nodes.Element;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreDataset;
import us.poliscore.dataset.augmentation.PoliscoreScrapedLegislatorData;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.service.storage.S3PersistenceService;

/**
 * Fetches images from congress.gov for all the legislators and uploads them to our S3 repository.
 * 
 * TODO : Legislators that do not have photos on congress.gov might have photos on bioguide.congress.gov. For example:
 * https://bioguide.congress.gov/search/bio/L000592
 * 
 * We could (or should?) fetch these from here:
 * https://github.com/unitedstates/images
 */
@QuarkusMain(name="StateLegislatorImageFetcher")
public class StateLegislatorImageFetcher extends AbstractLegislatorImageFetcher implements QuarkusApplication {
	
	protected S3PersistenceService s3;
	
	@SneakyThrows
	@Override
	protected Optional<byte[]> fetchImage(Legislator leg, PoliscoreDataset dataset) {
		val memberUrl = getOfficialUrl(leg, dataset);
	    if (memberUrl == null) return null;
	    
	    String url = scrapeImageUrlFromMemberPage(memberUrl, leg, dataset);
	    
	    if (url == null) return Optional.empty();

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
	            Log.warn("[" + leg.getCode() + "] Received " + status + " (rate limit). Waiting " + backoffMs + "ms...");
	            Thread.sleep(backoffMs);
	            backoffMs = Math.min(backoffMs * 2, 60000); // max 1 minute
	            continue;
	        }

	        if (status >= 400) {
	            val body = IOUtils.toString(is, "UTF-8");
	            Log.warn("[" + leg.getCode() + "] HTTP " + status + ": " + body.substring(0, Math.min(body.length(), 300)));
	            return Optional.empty(); // don't retry 404s, 500s, etc.
	        }

	        byte[] image = IOUtils.toByteArray(is);
	        if (!isJPEG(image)) {
	            Log.warn("[" + leg.getCode() + "] Invalid image data. Waiting " + backoffMs + "ms...");
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
	public String scrapeImageUrlFromMemberPage(String officialUrl, Legislator leg, PoliscoreDataset dataset) {
	    // Reuse the exact SSL setup as before
	    KeyStore keyStore = KeyStore.getInstance("PKCS12");
	    keyStore.load(StateLegislatorImageFetcher.class.getResourceAsStream("keystore"), "changeit".toCharArray());

	    SSLContext sslContext = SSLContexts.custom()
	        .loadKeyMaterial(keyStore, null)
	        .build();

	    CloseableHttpClient httpClient = HttpClients.custom()
	        .setSSLContext(sslContext)
	        .build();

	    val get = new HttpGet(officialUrl);
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
	    val imgs = Jsoup.parse(html).select("img");
	    
	    Element candidate = null;
	    for (val img : imgs) {
	    	if (img.attr("src").toLowerCase().contains(leg.getName().getFirst().toLowerCase())
	    			|| img.attr("src").toLowerCase().contains(leg.getName().getLast().toLowerCase())) {
	    		candidate = img;
	    		break;
	    	}
	    }
	    
	    if (candidate == null) return null;

//	    if (img == null) {
//	        return null;
//	    } else {
//	        return "https://www.congress.gov" + img.attr("src");
//	    }
	    
	    String url = candidate.attr("src");
	    if (!url.contains("https://") && !url.contains("http://")) {
	    	// TODO : Absolute versus relative
	    }
	    
	    return url;
	}
	
	protected String getOfficialUrl(Legislator leg, PoliscoreDataset dataset) {
		val op = s3.get(leg.getId(), PoliscoreScrapedLegislatorData.class);
		
		if (op.isPresent()) {
			return op.get().getOfficialUrl();
		}
		
		return null;
	}
	
	public static void main(String[] args) {
		Quarkus.run(StateLegislatorImageFetcher.class, args);
	}
	
}
