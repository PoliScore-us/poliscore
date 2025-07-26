package us.poliscore.dataset.augmentation;

import java.io.InputStream;
import java.security.KeyStore;

import javax.net.ssl.SSLContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import jakarta.inject.Inject;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreDataset;
import us.poliscore.images.StateLegislatorImageFetcher;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislativeTerm;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.storage.S3PersistenceService;

/**
 * Fetches additional data for state-level legislators which is not served by legiscan but we want for our app. The data is archived on S3 and fetched during database build.
 */
public class PoliscoreDatasetAugmentor implements QuarkusApplication {
	
	public static final String BIRTHDAY_LOOKUP_SYSTEM_PROMPT =
		    "You are an expert researcher with access to google searches. " +
		    "Your mission is to perform google searches and find the birthday for every legislator from a specific U.S. state (a complete list will be provided), and return the results as a JSON array of JSON objects." +
		    "The format of your JSON response will be as follows: [{'name': '<full name, exactly as given to you>', 'birthday': 'YYYY-MM-DD'}] " +
		    "Each JSON object should contain the legislator's full name (exactly as you have been provided) and birthday in ISO format (YYYY-MM-DD). " +
		    "If a birthday cannot be found, return 'UNKNOWN' for that entry. " +
		    "Respond only with the JSON array, and nothing else.";
	
	@Inject
	protected GovernmentDataService data;
	
	@Inject
	protected S3PersistenceService s3;
	
	@Inject
	protected CachedLegiscanService legiscan;
	
	@Inject
	protected OpenAIService openai;
	
	public void augmentLegislators(PoliscoreDataset dataset) {
		for (Legislator leg : dataset.query(Legislator.class)) {
			val addendum = s3.get(PoliscoreScrapedLegislatorData.generateId(leg.getId()), PoliscoreScrapedLegislatorData.class);
			
			if (addendum.isPresent()) {
				if (StringUtils.isNotBlank(addendum.get().getOfficialUrl()))
					leg.setOfficialUrl(addendum.get().getOfficialUrl());
				
				if (addendum.get().getBirthday() != null)
					leg.setBirthday(addendum.get().getBirthday());
			} else {
				leg.setOfficialUrl(null);
			}
		}
	}
	
	public void fetchData() {
		data.importAllDatasets();
		
		for (val dataset : data.getBuildDatasets()) {
			fetchData(dataset);
		}
	}
	
	public void fetchData(PoliscoreDataset dataset) {
		fetchOfficalUrls(dataset);
		openAiDeepResearch(dataset);
	}
	
	public void fetchOfficalUrls(PoliscoreDataset dataset) {
		if (dataset.getSession().getNamespace().equals(LegislativeNamespace.US_CONGRESS)) return;
		
		int success = 0;
		int skipped = 0;
		
		Log.info("Building list of legislators to fetch. This will take a minute...");
		
		val legs = dataset.query(Legislator.class).stream()
				.filter(l -> l.getOfficialUrl() == null)
				.toList();
		
		if (legs.size() == 0) {
			Log.info("No legislators missing official url.");
			return;
		}
		
		Log.info("About to fetch official url for " + legs.size() + " legislators.");
		
		for (Legislator leg : legs)	{
			try
			{
				if (fetchOfficialUrl(leg, dataset)) {
					success++;
				} else {
					skipped++;
				}
			}
			catch (Throwable t)
			{
				Log.warn("Could not find image for congressman " + leg.getName().getOfficial_full() + " " + leg.getCode());
				t.printStackTrace();
			}
		}
		
		Log.info("Successfully fetched data for " + success + " legislators. Skipped " + skipped);
	}
	
	public boolean fetchOfficialUrl(Legislator leg, PoliscoreDataset dataset) {
		val existing = s3.get(PoliscoreScrapedLegislatorData.generateId(leg.getId()), PoliscoreScrapedLegislatorData.class);
		if (existing.isPresent() && existing.get().getOfficialUrl() != null) return false;
		
		val person = legiscan.getPerson(leg.getLegiscanId());
		
		PoliscoreScrapedLegislatorData scraped = null;
		String[] candidates = new String[] {
				guessOfficialUrl(leg, dataset, null),
				guessOfficialUrl(leg, dataset, person.getNickname())
		};
		
		int i = 0;
		while (scraped == null && i < candidates.length) {
			scraped = scrapeDataFromOfficialUrl(candidates[i], leg, dataset);
			++i;
		}
		
		if (scraped != null) {
			if (existing.isPresent()) {
				scraped.setBirthday(existing.get().getBirthday());
			}
			
			s3.put(scraped);
		}
		
		return scraped != null;
	}
	
	@SneakyThrows
	public void openAiDeepResearch(PoliscoreDataset dataset) {
		if (dataset.getSession().getNamespace().equals(LegislativeNamespace.US_CONGRESS)) return;
		
	    val legs = dataset.query(Legislator.class).stream()
	            .filter(l -> l.getBirthday() == null)
	            .toList();

	    if (legs.isEmpty()) {
	        Log.info("No legislators missing birthdays.");
	        return;
	    }

	    Log.info("About to deep research birthdays for " + legs.size() + " legislators...");

	    StringBuilder sb = new StringBuilder();
	    sb.append(dataset.getSession().getNamespace().getDescription() + " State Legislature\n");
	    sb.append("List of legislators:\n");

	    for (Legislator leg : legs) {
	        sb.append("- ").append(leg.getName().getOfficial_full()).append("\n");
	    }

	    String userPrompt = sb.toString();
	    String response = openai.chat(BIRTHDAY_LOOKUP_SYSTEM_PROMPT, userPrompt, "o3-deep-research");

	    Log.info("OpenAI response:\n" + response);

	    val mapper = new com.fasterxml.jackson.databind.ObjectMapper();
	    val array = mapper.readTree(response);

	    int updated = 0;
	    int skipped = 0;

	    for (val node : array) {
	        String fullName = node.get("name").asText();
	        String birthdayStr = node.get("birthday").asText();

	        if ("UNKNOWN".equalsIgnoreCase(birthdayStr.toUpperCase())) {
	            skipped++;
	            continue;
	        }

	        val legOpt = legs.stream()
	            .filter(l -> l.getName().getOfficial_full().equalsIgnoreCase(fullName))
	            .findFirst();

	        if (legOpt.isPresent()) {
	            val leg = legOpt.get();

	            try {
	                val parsedDate = java.time.LocalDate.parse(birthdayStr);
	                leg.setBirthday(parsedDate);

	                val scraped = new PoliscoreScrapedLegislatorData();
	                scraped.setId(PoliscoreScrapedLegislatorData.generateId(leg.getId()));
	                scraped.setBirthday(parsedDate);
	                scraped.setOfficialUrl(leg.getOfficialUrl());

	                s3.put(scraped);
	                updated++;

	                Log.info("Set and persisted birthday for " + fullName + ": " + parsedDate);
	            } catch (Exception e) {
	                skipped++;
	                Log.warn("Failed to parse or persist birthday for " + fullName + ": " + birthdayStr, e);
	            }
	        } else {
	            skipped++;
	            Log.warn("Could not match OpenAI result to any known legislator: " + fullName);
	        }
	    }

	    Log.info("Successfully updated " + updated + " legislators. Skipped " + skipped + ".");
	}
	
	@SneakyThrows
	public PoliscoreScrapedLegislatorData scrapeDataFromOfficialUrl(String officialUrl, Legislator leg, PoliscoreDataset dataset) {
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

	    val data = new PoliscoreScrapedLegislatorData();
	    data.setId(PoliscoreScrapedLegislatorData.generateId(leg.getId()));
	    data.setOfficialUrl(officialUrl);
	    return data;
	}
	
	public static String guessOfficialUrl(Legislator leg, PoliscoreDataset dataset, String altFirstName) {
	    if (leg == null || dataset == null || dataset.getSession() == null || leg.getName() == null || leg.getTerms().isEmpty())
	        return null;

	    LegislativeNamespace ns = dataset.getSession().getNamespace();
	    Legislator.LegislatorName name = leg.getName();
	    LegislativeTerm lastTerm = leg.getTerms().last();

	    String first;
	    if (StringUtils.isNotBlank(altFirstName))
	    	first = altFirstName.toLowerCase().replace(" ", "-");
	    else
	    	first = name.getFirst() == null ? "" : name.getFirst().toLowerCase().replace(" ", "-");
	    
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
		Quarkus.run(PoliscoreDatasetAugmentor.class, args);
		Quarkus.asyncExit(0);
	}
	
	@Override
    public int run(String... args) throws Exception {
		
		fetchData();
        
        Quarkus.waitForExit();
        return 0;
    }
	
}
