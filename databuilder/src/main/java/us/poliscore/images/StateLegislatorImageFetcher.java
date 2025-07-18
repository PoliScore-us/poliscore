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
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislativeTerm;

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
	
	@SneakyThrows
	@Override
	protected Optional<byte[]> fetchImage(Legislator leg, PoliscoreDataset dataset) {
	    String url = scrapeImageUrlFromMemberPage(leg, dataset);
	    
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
	private String scrapeImageUrlFromMemberPage(Legislator leg, PoliscoreDataset dataset) {
	    val memberUrl = getOfficialUrl(leg, dataset);

	    // Reuse the exact SSL setup as before
	    KeyStore keyStore = KeyStore.getInstance("PKCS12");
	    keyStore.load(StateLegislatorImageFetcher.class.getResourceAsStream("keystore"), "changeit".toCharArray());

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
	
	public String getOfficialUrl(Legislator leg, PoliscoreDataset dataset) {
	    if (leg == null || dataset == null || dataset.getSession() == null || leg.getName() == null || leg.getTerms().isEmpty())
	        return null;

	    LegislativeNamespace ns = dataset.getSession().getNamespace();
	    Legislator.LegislatorName name = leg.getName();
	    LegislativeTerm lastTerm = leg.getTerms().last();

	    String first = name.getFirst() == null ? "" : name.getFirst().toLowerCase().replace(" ", "-");
	    String last = name.getLast() == null ? "" : name.getLast().toLowerCase().replace(" ", "-");
	    String id = leg.getId();
	    String code = id != null ? id.substring(id.lastIndexOf("/") + 1) : "";
	    int year = dataset.getSession().getStartDate().getYear();

	    if (ns == LegislativeNamespace.US_CONGRESS) {
	        return "https://www.congress.gov/member/" + first + "-" + last + "/" + code;
	    } else if (ns == LegislativeNamespace.US_ALABAMA) {
	        return "https://www.legislature.state.al.us/aliswww/ISD/ALRepresentative.aspx?NAME=" + name.getLast();
	    } else if (ns == LegislativeNamespace.US_ALASKA) {
	        return "http://akleg.gov/legislator.php?id=" + id;
	    } else if (ns == LegislativeNamespace.US_ARIZONA) {
	        return "https://www.azleg.gov/MemberRoster/?body=" + (lastTerm.getChamber() == LegislativeChamber.UPPER ? "S" : "H");
	    } else if (ns == LegislativeNamespace.US_ARKANSAS) {
	        return "https://www.arkleg.state.ar.us/Legislators/Detail?member=" + name.getLast();
	    } else if (ns == LegislativeNamespace.US_CALIFORNIA) {
	        return "https://findyourrep.legislature.ca.gov/";
	    } else if (ns == LegislativeNamespace.US_COLORADO) {
	        return "https://leg.colorado.gov/legislators/" + first + "-" + last;
	    } else if (ns == LegislativeNamespace.US_CONNECTICUT) {
	        return "https://www.cga.ct.gov/asp/menu/cgafindleg.asp";
	    } else if (ns == LegislativeNamespace.US_DELAWARE) {
	        return "https://legis.delaware.gov/Legislator-Detail?personId=" + id;
	    } else if (ns == LegislativeNamespace.US_FLORIDA) {
	        return lastTerm.getChamber() == LegislativeChamber.UPPER
	            ? "https://www.flsenate.gov/Senators/" + lastTerm.getDistrict()
	            : "https://www.myfloridahouse.gov/Sections/Representatives/representatives.aspx";
	    } else if (ns == LegislativeNamespace.US_GEORGIA) {
	        return "https://www.legis.ga.gov/members/" + (lastTerm.getChamber() == LegislativeChamber.UPPER ? "senate" : "house") + "/" + id;
	    } else if (ns == LegislativeNamespace.US_HAWAII) {
	        return "https://www.capitol.hawaii.gov/legislator.aspx?member=" + name.getLast();
	    } else if (ns == LegislativeNamespace.US_IDAHO) {
	        return "https://legislature.idaho.gov/legislators/membership/";
	    } else if (ns == LegislativeNamespace.US_ILLINOIS) {
	        return "https://www.ilga.gov/house/Rep.asp?MemberID=" + id;
	    } else if (ns == LegislativeNamespace.US_INDIANA) {
	        return "https://iga.in.gov/legislative/2024/legislators/" + id;
	    } else if (ns == LegislativeNamespace.US_IOWA) {
	        return "https://www.legis.iowa.gov/legislators/legislator?ga=" + year + "&personID=" + id;
	    } else if (ns == LegislativeNamespace.US_KANSAS) {
	        return "http://www.kslegislature.org/li/b2023_24/members/" + id;
	    } else if (ns == LegislativeNamespace.US_KENTUCKY) {
	        return "https://legislature.ky.gov/Legislators/" + (lastTerm.getChamber() == LegislativeChamber.UPPER ? "senate" : "house") + "/Pages/default.aspx";
	    } else if (ns == LegislativeNamespace.US_LOUISIANA) {
	        return "https://www.legis.la.gov/legis/FindMyLegislators.aspx";
	    } else if (ns == LegislativeNamespace.US_MAINE) {
	        return "https://legislature.maine.gov/house/house/MemberProfiles/ListAlphaTown";
	    } else if (ns == LegislativeNamespace.US_MARYLAND) {
	        return "https://mgaleg.maryland.gov/mgawebsite/Members/Index/";
	    } else if (ns == LegislativeNamespace.US_MASSACHUSETTS) {
	        return "https://malegislature.gov/Legislators/Members/";
	    } else if (ns == LegislativeNamespace.US_MICHIGAN) {
	        return "https://www.house.mi.gov/all-representatives";
	    } else if (ns == LegislativeNamespace.US_MINNESOTA) {
	        return "https://www.leg.mn.gov/legdb/";
	    } else if (ns == LegislativeNamespace.US_MISSISSIPPI) {
	        return "https://billstatus.ls.state.ms.us/members/";
	    } else if (ns == LegislativeNamespace.US_MISSOURI) {
	        return "https://house.mo.gov/MemberDetails.aspx?year=" + year + "&code=" + id;
	    } else if (ns == LegislativeNamespace.US_MONTANA) {
	        return "https://leg.mt.gov/legislator-information/";
	    } else if (ns == LegislativeNamespace.US_NEBRASKA) {
	        return "https://nebraskalegislature.gov/senators/senator_find.php";
	    } else if (ns == LegislativeNamespace.US_NEVADA) {
	        return "https://www.leg.state.nv.us/App/Legislator/A/Assembly/";
	    } else if (ns == LegislativeNamespace.US_NEW_HAMPSHIRE) {
	        return "https://www.gencourt.state.nh.us/house/members/default.aspx";
	    } else if (ns == LegislativeNamespace.US_NEW_JERSEY) {
	        return "https://www.njleg.state.nj.us/legislative-roster";
	    } else if (ns == LegislativeNamespace.US_NEW_MEXICO) {
	        return "https://www.nmlegis.gov/Members/Legislator_List";
	    } else if (ns == LegislativeNamespace.US_NEW_YORK) {
	        return "https://nyassembly.gov/mem/";
	    } else if (ns == LegislativeNamespace.US_NORTH_CAROLINA) {
	        return "https://www.ncleg.gov/Members/Biography/";
	    } else if (ns == LegislativeNamespace.US_NORTH_DAKOTA) {
	        return lastTerm.getChamber() == LegislativeChamber.UPPER
	            ? "https://www.legis.nd.gov/assembly/67-2021/members/senate"
	            : "https://www.legis.nd.gov/assembly/67-2021/members/house";
	    } else if (ns == LegislativeNamespace.US_OHIO) {
	        return "https://www.ohiohouse.gov/members/all";
	    } else if (ns == LegislativeNamespace.US_OKLAHOMA) {
	        return "https://www.okhouse.gov/Members/";
	    } else if (ns == LegislativeNamespace.US_OREGON) {
	        return "https://www.oregonlegislature.gov/legislators-and-staff";
	    } else if (ns == LegislativeNamespace.US_PENNSYLVANIA) {
	        return "https://www.legis.state.pa.us/cfdocs/legis/home/member_information/house_bio.cfm";
	    } else if (ns == LegislativeNamespace.US_RHODE_ISLAND) {
	        return lastTerm.getChamber() == LegislativeChamber.UPPER
	            ? "https://www.rilegislature.gov/senators/"
	            : "https://www.rilegislature.gov/representatives/";
	    } else if (ns == LegislativeNamespace.US_SOUTH_CAROLINA) {
	        return "https://www.scstatehouse.gov/member.php?chamber=" + (lastTerm.getChamber() == LegislativeChamber.UPPER ? "S" : "H");
	    } else if (ns == LegislativeNamespace.US_SOUTH_DAKOTA) {
	        return "https://sdlegislature.gov/Legislators/Profile/Session/2024/" + (lastTerm.getChamber() == LegislativeChamber.UPPER ? "Upper" : "Lower");
	    } else if (ns == LegislativeNamespace.US_TENNESSEE) {
	        return "https://www.capitol.tn.gov/" + (lastTerm.getChamber() == LegislativeChamber.UPPER ? "senate" : "house") + "/members/";
	    } else if (ns == LegislativeNamespace.US_TEXAS) {
	        return "https://capitol.texas.gov/Members/" + (lastTerm.getChamber() == LegislativeChamber.UPPER ? "senate" : "house") + ".aspx?district=" + lastTerm.getDistrict();
	    } else if (ns == LegislativeNamespace.US_UTAH) {
	        return "https://le.utah.gov/asp/interim/Main.asp?LegCode=" + id;
	    } else if (ns == LegislativeNamespace.US_VERMONT) {
	        return "https://legislature.vermont.gov/people/";
	    } else if (ns == LegislativeNamespace.US_VIRGINIA) {
	        return "https://whosmy.virginiageneralassembly.gov/";
	    } else if (ns == LegislativeNamespace.US_WASHINGTON) {
	        return "https://app.leg.wa.gov/rosters/Members/";
	    } else if (ns == LegislativeNamespace.US_WASHINGTON_DC) {
	        return "https://dccouncil.gov/councilmembers/";
	    } else if (ns == LegislativeNamespace.US_WEST_VIRGINIA) {
	        return lastTerm.getChamber() == LegislativeChamber.UPPER
	            ? "https://www.wvlegislature.gov/Senate1/roster.cfm"
	            : "https://www.wvlegislature.gov/House/roster.cfm";
	    } else if (ns == LegislativeNamespace.US_WISCONSIN) {
	        return "https://docs.legis.wisconsin.gov/2023/legislators/" + (lastTerm.getChamber() == LegislativeChamber.UPPER ? "senate" : "assembly");
	    } else if (ns == LegislativeNamespace.US_WYOMING) {
	        return "https://www.wyoleg.gov/Legislators";
	    } else {
	        return null;
	    }
	}

	
	public static void main(String[] args) {
		Quarkus.run(StateLegislatorImageFetcher.class, args);
	}
	
}
