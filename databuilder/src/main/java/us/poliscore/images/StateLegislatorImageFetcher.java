package us.poliscore.images;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
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
import jakarta.inject.Inject;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.dataset.augmentation.PoliscoreDatasetAugmentor;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.legiscan.service.CachedLegiscanService;
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

	@Inject
	protected CachedLegiscanService legiscan;
	
	@SneakyThrows
	@Override
	protected Optional<byte[]> fetchImage(Legislator leg, PoliscoreDatasetIF dataset) {
	    String url = getLegiscanImageUrl(leg);
	    if (url == null) {
	        String memberUrl = leg.getOfficialUrl();
	        if (!isAbsoluteHttpUrl(memberUrl)) {
	            memberUrl = PoliscoreDatasetAugmentor.guessOfficialUrl(leg, dataset, null);
	        }
	        if (!isAbsoluteHttpUrl(memberUrl)) return Optional.empty();
	        url = scrapeImageUrlFromMemberPage(memberUrl, leg, dataset);
	    }
	    
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
	        if (!isValidImage(image)) {
	            Log.warn("Error fetching url [" + url + "] for legislator [" + leg.getCode() + "]: invalid image data. Waiting " + backoffMs + "ms...");
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

	private String getLegiscanImageUrl(Legislator leg) {
		if (leg.getLegiscanId() == null) return null;
		try {
			val person = legiscan.getPerson(leg.getLegiscanId());
			if (person.getBio() == null || person.getBio().getSocial() == null) return null;
			String imageUrl = person.getBio().getSocial().get("image");
			return isAbsoluteHttpUrl(imageUrl) ? imageUrl : null;
		} catch (RuntimeException e) {
			Log.debug("Unable to read LegiScan image URL for legislator " + leg.getCode(), e);
			return null;
		}
	}

	static boolean isAbsoluteHttpUrl(String value) {
		if (value == null || value.isBlank()) return false;
		try {
			URI uri = new URI(value.trim());
			return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
					&& uri.getHost() != null;
		} catch (URISyntaxException e) {
			return false;
		}
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
	public String scrapeImageUrlFromMemberPage(String officialUrl, Legislator leg, PoliscoreDatasetIF dataset) {
		if (!isAbsoluteHttpUrl(officialUrl)) return null;

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
	    			|| img.attr("src").toLowerCase().contains(leg.getName().getLast().toLowerCase())
	    			|| img.attr("alt").toLowerCase().contains(leg.getName().getFirst().toLowerCase())
	    			|| img.attr("alt").toLowerCase().contains(leg.getName().getLast().toLowerCase())
	    			|| img.attr("src").toLowerCase().contains("images/legislators/house") || img.attr("src").toLowerCase().contains("images/legislators/senate")
	    			|| img.attr("src").toLowerCase().contains("images/members")) {
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
	    
	    String url = toAbsoluteImageUrl(candidate, officialUrl);
	    
	    if (url == null || url.isBlank()) return null;
	    
	    return url;
	}
	
	public static String toAbsoluteImageUrl(Element candidate, String pageUrl) {
	    if (candidate == null || pageUrl == null || pageUrl.isBlank()) {
	        return null;
	    }

	    try {
	        // 1️⃣ Try Jsoup’s built-in resolution first
	        String abs = candidate.absUrl("src");
	        if (abs != null && !abs.isBlank()) {
	            URI absUri = new URI(abs);
	            if (absUri.getScheme() != null && absUri.getHost() != null) {
	                return abs;
	            }
	        }

	        // 2️⃣ Fallback to raw src attribute
	        String src = candidate.attr("src");
	        if (src == null || src.isBlank()) {
	            return null;
	        }

	        src = src.trim();

	        URI base = new URI(pageUrl);

	        // 3️⃣ Protocol-relative: //cdn.site.com/image.jpg
	        if (src.startsWith("//")) {
	            String scheme = base.getScheme() != null ? base.getScheme() : "https";
	            return scheme + ":" + src;
	        }

	        // 4️⃣ Already absolute?
	        URI srcUri = new URI(src);
	        if (srcUri.getScheme() != null && srcUri.getHost() != null) {
	            return src;
	        }

	        // 5️⃣ Relative → resolve against base
	        URI resolved = base.resolve(src);
	        if (resolved.getScheme() != null && resolved.getHost() != null) {
	            return resolved.toString();
	        }

	    } catch (URISyntaxException e) {
	        // swallow and return null — scraper shouldn’t crash
	    }

	    return null;
	}
	
//	protected String getOfficialUrl(Legislator leg, PoliscoreDataset dataset) {
//		val op = s3.get(leg.getId(), PoliscoreScrapedLegislatorData.class);
//		
//		if (op.isPresent()) {
//			return op.get().getOfficialUrl();
//		}
//		
//		return null;
//	}
	
	public static void main(String[] args) {
		Quarkus.run(StateLegislatorImageFetcher.class, args);
	}
	
}
