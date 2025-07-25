package us.poliscore.press;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import lombok.Data;
import lombok.experimental.UtilityClass;
import us.poliscore.legiscan.view.LegiscanBillType;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.Legislator.LegislatorName;

/**
 * Utility class for recognizing how likely an article is about a given bill,
 * considering both article text and source URL. Returns a normalized confidence score [0..1].
 */

// TODO : Check for article date. If it was published before the bill existed, then it's not about this bill.

@UtilityClass
public class BillArticleRecognizer {

	/**
	 * URLs for which we always return zero confidence
	 * 
	 * These URLS are typically congressional trackers and will not provide us with interpretations or news about a bill and need to be blacklisted
	 * because otherwise they will result in a very high confidence score.
	 * */
    private static final List<String> URL_BLACKLIST = Arrays.asList(
    	"poliscore.us",
    	"fastdemocracy.com",
        "congress.gov",
        "govtrack.us",
        "quiverquant.com",
        "house.gov",
        "billtrack50.com",
        "opencongress.org",
        "legiscan.com",
        "clerk.house.gov",
        "clerk.senate.gov",
        "cbo.gov",
        "whitegouse.gov",
        "senate.gov",
        "issuevoter.org",
        "trackbill.com"
    );
	
    // Primary date formats to check
    private static final List<DateTimeFormatter> DATE_FORMATTERS = Arrays.asList(
        DateTimeFormatter.ofPattern("MMMM d, yyyy"),
        DateTimeFormatter.ofPattern("MMM d, yyyy"),
        DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
    );

    // BillType tokens
    private static final Map<String, List<String>> TYPE_TOKENS = new HashMap<>();
    static {
    	// Congressional bill types
        TYPE_TOKENS.put(CongressionalBillType.HR.name(), Arrays.asList("hr", "h.r.", "house bill"));
        TYPE_TOKENS.put(CongressionalBillType.S.name(), Arrays.asList("s", "s.", "senate bill"));
        TYPE_TOKENS.put(CongressionalBillType.SCONRES.name(), Collections.singletonList("s.con.res."));
        TYPE_TOKENS.put(CongressionalBillType.HRES.name(), Collections.singletonList("h.res."));
        TYPE_TOKENS.put(CongressionalBillType.HCONRES.name(),Collections.singletonList("h.con.res."));
        TYPE_TOKENS.put(CongressionalBillType.SJRES.name(), Collections.singletonList("s.j.res."));
        TYPE_TOKENS.put(CongressionalBillType.SRES.name(),  Collections.singletonList("s.res."));
        TYPE_TOKENS.put(CongressionalBillType.HJRES.name(), Collections.singletonList("h.j.res."));
        
        // Legiscan/state bill types
        TYPE_TOKENS.put(LegiscanBillType.BILL.getCode(),    Arrays.asList("hb", "sb", "bill"));
        TYPE_TOKENS.put(LegiscanBillType.RESOLUTION.getCode(), Arrays.asList("resolution", "hr", "sr"));
        TYPE_TOKENS.put(LegiscanBillType.CONCURRENT_RESOLUTION.getCode(), Arrays.asList("concurrent resolution", "hcr", "scr"));
        TYPE_TOKENS.put(LegiscanBillType.JOINT_RESOLUTION.getCode(), Arrays.asList("joint resolution", "hjr", "sjr"));
        TYPE_TOKENS.put(LegiscanBillType.JOINT_RESOLUTION_CONSTITUTIONAL_AMENDMENT.getCode(), Arrays.asList("constitutional amendment", "joint resolution constitutional amendment"));
        TYPE_TOKENS.put(LegiscanBillType.EXECUTIVE_ORDER.getCode(), Collections.singletonList("executive order"));
        TYPE_TOKENS.put(LegiscanBillType.CONSTITUTIONAL_AMENDMENT.getCode(), Arrays.asList("constitutional amendment", "amendment"));
        TYPE_TOKENS.put(LegiscanBillType.MEMORIAL.getCode(), Collections.singletonList("memorial"));
        TYPE_TOKENS.put(LegiscanBillType.CLAIM.getCode(), Collections.singletonList("claim"));
        TYPE_TOKENS.put(LegiscanBillType.COMMENDATION.getCode(), Collections.singletonList("commendation"));
        TYPE_TOKENS.put(LegiscanBillType.COMMITTEE_STUDY_REQUEST.getCode(), Arrays.asList("committee study request", "csr"));
        TYPE_TOKENS.put(LegiscanBillType.JOINT_MEMORIAL.getCode(), Collections.singletonList("joint memorial"));
        TYPE_TOKENS.put(LegiscanBillType.PROCLAMATION.getCode(), Collections.singletonList("proclamation"));
        TYPE_TOKENS.put(LegiscanBillType.STUDY_REQUEST.getCode(), Collections.singletonList("study request"));
        TYPE_TOKENS.put(LegiscanBillType.ADDRESS.getCode(), Collections.singletonList("address"));
        TYPE_TOKENS.put(LegiscanBillType.CONCURRENT_MEMORIAL.getCode(), Collections.singletonList("concurrent memorial"));
        TYPE_TOKENS.put(LegiscanBillType.INITIATIVE.getCode(), Arrays.asList("initiative", "ballot initiative"));
        TYPE_TOKENS.put(LegiscanBillType.PETITION.getCode(), Collections.singletonList("petition"));
        TYPE_TOKENS.put(LegiscanBillType.STUDY_BILL.getCode(), Arrays.asList("study bill", "sb"));
        TYPE_TOKENS.put(LegiscanBillType.INITIATIVE_PETITION.getCode(), Arrays.asList("initiative petition", "ip"));
        TYPE_TOKENS.put(LegiscanBillType.REPEAL_BILL.getCode(), Arrays.asList("repeal bill", "rb"));
        TYPE_TOKENS.put(LegiscanBillType.REMONSTRATION.getCode(), Collections.singletonList("remonstration"));
        TYPE_TOKENS.put(LegiscanBillType.COMMITTEE_BILL.getCode(), Arrays.asList("committee bill", "cb"));
    }

    // Chamber tokens
    private static final Map<LegislativeChamber, List<String>> CHAMBER_TOKENS = new EnumMap<>(LegislativeChamber.class);
    static {
        CHAMBER_TOKENS.put(LegislativeChamber.LOWER, Arrays.asList(
            "house of representatives", "u.s. house", "us house", "house"));
        CHAMBER_TOKENS.put(LegislativeChamber.UPPER, Arrays.asList(
            "senate", "u.s. senate", "us senate"));
        CHAMBER_TOKENS.put(LegislativeChamber.JOINT, Arrays.asList(
                "joint chamber"));
    }

    // Political context tokens
    private static final Map<String, Float> POLITICAL_TOKENS = new LinkedHashMap<>();
    static {
        POLITICAL_TOKENS.put("bill", 0.3f);
        POLITICAL_TOKENS.put("law", 0.2f);
        POLITICAL_TOKENS.put("legislation", 0.2f);
        POLITICAL_TOKENS.put("amendment", 0.1f);
        POLITICAL_TOKENS.put("committee", 0.1f);
        POLITICAL_TOKENS.put("vote", 0.1f);
        POLITICAL_TOKENS.put("congress", 0.1f);
        POLITICAL_TOKENS.put("senator", 0.1f);
        POLITICAL_TOKENS.put("representative", 0.1f);
    }

    // Namespace tokens with weights (extend for state namespaces)
    private static final Map<LegislativeNamespace, Map<String, Float>> NAMESPACE_TOKENS = new EnumMap<>(LegislativeNamespace.class);
    static {
        Map<String, Float> federal = new HashMap<>();
        federal.put("us congress", 1.0f);
        federal.put("u.s. congress", 1.0f);
        federal.put("congress", 0.8f);
        federal.put("federal", 0.5f);
        NAMESPACE_TOKENS.put(LegislativeNamespace.US_CONGRESS, federal);
        
        for (var ns : LegislativeNamespace.values()) {
        	if (!LegislativeNamespace.US_CONGRESS.equals(ns)) {
        		Map<String, Float> m = new HashMap<>();
        		m.put(ns.getDescription(), 1.0f);
        		NAMESPACE_TOKENS.put(ns, m);
        	}
    	}
    }

    // Known federal site indicators
    private static final List<String> FEDERAL_SITES = Arrays.asList(
        "congress.gov", "govinfo.gov", "federalregister.gov", "whitehouse.gov"
    );

    // Abstraction for state-level information
    @Data
    private static class StateInfo {
        private final String name;
        private final List<String> siteIndicators;
    }

    // Complete list of StateInfo entries for all 50 U.S. states
    private static final List<StateInfo> STATE_INFOS = Arrays.asList(
        new StateInfo("alabama", Arrays.asList("legislature.state.al.us", "al.com", "bhamnow.com", "montgomeryadvertiser.com")),
        new StateInfo("alaska", Arrays.asList("legis.state.ak.us", "juneauempire.com", "alaskapublic.org", "adn.com")),
        new StateInfo("arizona", Arrays.asList("azleg.gov", "azcentral.com", "tucson.com")),
        new StateInfo("arkansas", Arrays.asList("arkleg.state.ar.us", "arkansasonline.com", "thv11.com")),
        new StateInfo("california", Arrays.asList("leginfo.legislature.ca.gov", "latimes.com", "sacbee.com", "sfchronicle.com", "mercurynews.com", "sandiegouniontribune.com")),
        new StateInfo("colorado", Arrays.asList("leg.colorado.gov", "coloradopolitics.com", "denverpost.com", "sentinelcolorado.com", "westword.com", "coloradosun.com")),
        new StateInfo("connecticut", Arrays.asList("cga.ct.gov", "courant.com", "ctmirror.org", "nhregister.com")),
        new StateInfo("delaware", Arrays.asList("legis.delaware.gov", "delawareonline.com", "capegazette.com")),
        new StateInfo("florida", Arrays.asList("leg.state.fl.us", "tampabay.com", "orlandosentinel.com", "miamiherald.com", "sun-sentinel.com")),
        new StateInfo("georgia", Arrays.asList("legis.ga.gov", "ajc.com", "onlineathens.com", "savannahnow.com")),
        new StateInfo("hawaii", Arrays.asList("capitol.hawaii.gov", "staradvertiser.com", "hawaiitribune-herald.com")),
        new StateInfo("idaho", Arrays.asList("legislature.idaho.gov", "idahostatesman.com", "postregister.com")),
        new StateInfo("illinois", Arrays.asList("ilga.gov", "chicagotribune.com", "chicago.suntimes.com", "dailyherald.com")),
        new StateInfo("indiana", Arrays.asList("iga.in.gov", "indystar.com", "journalgazette.net")),
        new StateInfo("iowa", Arrays.asList("legis.iowa.gov", "desmoinesregister.com", "siouxcityjournal.com")),
        new StateInfo("kansas", Arrays.asList("kslegislature.org", "kansascity.com")),
        new StateInfo("kentucky", Arrays.asList("legislature.ky.gov", "kentucky.com", "bgdailynews.com")),
        new StateInfo("louisiana", Arrays.asList("legis.state.la.us", "theadvocate.com", "nola.com")),
        new StateInfo("maine", Arrays.asList("legislature.maine.gov", "pressherald.com", "bangordailynews.com")),
        new StateInfo("maryland", Arrays.asList("mgaleg.maryland.gov", "baltimoresun.com", "capitalgazette.com")),
        new StateInfo("massachusetts", Arrays.asList("malegislature.gov", "bostonglobe.com", "bostonherald.com")),
        new StateInfo("michigan", Arrays.asList("legislature.mi.gov", "mlive.com", "detroitnews.com", "freep.com")),
        new StateInfo("minnesota", Arrays.asList("leg.state.mn.us", "startribune.com", "pioneerpress.com")),
        new StateInfo("mississippi", Arrays.asList("legislature.ms.gov", "clarionledger.com", "sunherald.com")),
        new StateInfo("missouri", Arrays.asList("house.mo.gov", "stltoday.com", "kansascity.com")),
        new StateInfo("montana", Arrays.asList("leg.mt.gov", "missoulian.com", "billingsgazette.com")),
        new StateInfo("nebraska", Arrays.asList("nebraskalegislature.gov", "omaha.com", "journalstar.com")),
        new StateInfo("nevada", Arrays.asList("leg.state.nv.us", "reviewjournal.com", "rgj.com")),
        new StateInfo("new hampshire", Arrays.asList("gencourt.state.nh.us", "unionleader.com", "fosters.com")),
        new StateInfo("new jersey", Arrays.asList("njleg.state.nj.us", "nj.com", "app.com")),
        new StateInfo("new mexico", Arrays.asList("nmlegis.gov", "abqjournal.com", "santafenewmexican.com")),
        new StateInfo("new york", Arrays.asList("nyassembly.gov", "nytimes.com", "buffalonews.com", "newsday.com")),
        new StateInfo("north carolina", Arrays.asList("ncleg.gov", "newsobserver.com", "charlotteobserver.com")),
        new StateInfo("north dakota", Arrays.asList("legis.nd.gov", "inforum.com", "bismarcktribune.com")),
        new StateInfo("ohio", Arrays.asList("legislature.ohio.gov", "dispatch.com", "cleveland.com")),
        new StateInfo("oklahoma", Arrays.asList("oklegislature.gov", "oklahoman.com", "tulsaworld.com")),
        new StateInfo("oregon", Arrays.asList("oregonlegislature.gov", "oregonlive.com", "portlandtribune.com")),
        new StateInfo("pennsylvania", Arrays.asList("legis.state.pa.us", "philly.com", "inquirer.com")),
        new StateInfo("rhode island", Arrays.asList("rilegislature.gov", "providencejournal.com")),
        new StateInfo("south carolina", Arrays.asList("scstatehouse.gov", "thestate.com", "postandcourier.com")),
        new StateInfo("south dakota", Arrays.asList("sdlegislature.gov", "argusleader.com", "rapidcityjournal.com")),
        new StateInfo("tennessee", Arrays.asList("capitol.tn.gov", "tennessean.com", "knoxnews.com")),
        new StateInfo("texas", Arrays.asList("capitol.texas.gov", "houstonchronicle.com", "dallasnews.com")),
        new StateInfo("utah", Arrays.asList("le.utah.gov", "sltrib.com", "deseretnews.com")),
        new StateInfo("vermont", Arrays.asList("legislature.vermont.gov", "rutlandherald.com", "burlingtonfreepress.com")),
        new StateInfo("virginia", Arrays.asList("virginiageneralassembly.gov", "richmond.com", "dailyprogress.com")),
        new StateInfo("washington", Arrays.asList("leg.wa.gov", "seattletimes.com", "seattlepi.com")),
        new StateInfo("west virginia", Arrays.asList("wvlegislature.gov", "wvgazettemail.com", "charlestondailymail.com")),
        new StateInfo("wisconsin", Arrays.asList("legis.wisconsin.gov", "jsonline.com", "journaltimes.com")),
        new StateInfo("wyoming", Arrays.asList("wyoleg.gov", "cowboystatedaily.com", "trib.com"))
    );

    /**
     * Computes a confidence score [0..1] that the given article text and URL refer to the provided bill.
     */
    public float recognize(Bill bill, String article, String url) {
    	if (article.length() < 1000)
    		return 0f;
    	
    	if (article.contains("Quiver Quantitative"))
    		return 0f;
    	
    	String u = url.toLowerCase(Locale.ROOT);
        for (String bad : URL_BLACKLIST) {
            if (u.contains(bad)) {
                return 0f;
            }
        }
    	
        String text = article.toLowerCase(Locale.ROOT);

        float idScore        = scoreTypeNumber(bill, text);
        float chamberScore   = scoreChamber(bill, text);
        float sessionScore   = scoreSession(bill, text);
        float polScore       = scorePolitical(text);
        float sponsorScore   = scoreSponsor(bill, text);
        float cosponsorScore = scoreCosponsors(bill, text);
        float nameScore      = scoreName(bill, text);
        float nsScore        = scoreNamespace(bill, text);
        float dateScore      = scoreDate(bill, text);
        float urlScore       = scoreUrl(bill, url);

        // Tunable weights
        final float W_ID        = 0.30f;
        final float W_CHAMBER   = 0.05f;
        final float W_SESSION   = 0.05f;
        final float W_POLITICAL = 0.10f;
        final float W_SPONSOR   = 0.15f;
        final float W_COSPONSOR = 0.10f;
        final float W_NAME      = 0.10f;
        final float W_NAMESPACE = 0.10f;
        final float W_DATE      = 0.15f;
        final float W_URL       = 0.05f;

        float score = idScore * W_ID
                    + chamberScore * W_CHAMBER
                    + sessionScore * W_SESSION
                    + polScore * W_POLITICAL
                    + sponsorScore * W_SPONSOR
                    + cosponsorScore * W_COSPONSOR
                    + nameScore * W_NAME
                    + nsScore * W_NAMESPACE
                    + dateScore * W_DATE
                    + urlScore * W_URL;

        return Math.min(1f, Math.max(0f, score));
    }

    private float scoreTypeNumber(Bill bill, String text) {
        int number = bill.getNumber();
        String type = bill.getType();
        return TYPE_TOKENS.getOrDefault(type, Collections.emptyList())
                .stream()
                .anyMatch(tok -> Pattern.compile("\\b" + Pattern.quote(tok) + "\\W*" + number + "\\b",
                        Pattern.CASE_INSENSITIVE)
                        .matcher(text)
                        .find())
            ? 1f
            : 0f;
    }

    private float scoreChamber(Bill bill, String text) {
        LegislativeChamber cham = bill.getOriginatingChamber();
        
        return CHAMBER_TOKENS.getOrDefault(cham, Collections.emptyList())
                .stream()
                .anyMatch(text::contains)
            ? 1f
            : 0f;
    }

    private float scoreSession(Bill bill, String text) {
    	String pat;
    	if (bill.getNamespace() == LegislativeNamespace.US_CONGRESS) {
    	    pat = bill.getSessionCode() + "(st|nd|rd|th)? congress";
    	} else {
    	    return 0f;
    	}
        
        return Pattern.compile(pat, Pattern.CASE_INSENSITIVE)
                .matcher(text)
                .find()
            ? 1f
            : 0f;
    }

    private float scorePolitical(String text) {
        float sumW = 0f, matchW = 0f;
        for (var e : POLITICAL_TOKENS.entrySet()) {
            sumW += Math.abs(e.getValue());
            if (text.contains(e.getKey())) {
                matchW += e.getValue();
            }
        }
        return sumW == 0f ? 0f : Math.min(1f, Math.max(0f, matchW / sumW));
    }

    private float scoreSponsor(Bill bill, String text) {
        LegislatorName n = bill.getSponsor().getName();
        float full  = contains(text, n.getOfficial_full()) ? 1f : 0f;
        float combo = contains(text, n.getFirst() + " " + n.getLast()) ? 0.5f : 0f;
        float first = contains(text, n.getFirst()) ? 0.2f : 0f;
        float last  = contains(text, n.getLast()) ? 0.2f : 0f;
        return Math.min(1f, full + combo + Math.max(first, last));
    }

    private float scoreCosponsors(Bill bill, String text) {
        var cos = bill.getCosponsors();
        if (cos == null || cos.isEmpty()) return 0f;
        long total = cos.size();
        long matched = cos.stream().filter(sp -> {
            LegislatorName n = sp.getName();
            boolean okFull  = contains(text, n.getOfficial_full());
            boolean okParts = contains(text, n.getFirst()) && contains(text, n.getLast());
            return okFull || okParts;
        }).count();
        return (float) matched / total;
    }

    private float scoreName(Bill bill, String text) {
        String name = bill.getName();
        return (name != null && contains(text, name)) ? 1f : 0f;
    }

    private float scoreNamespace(Bill bill, String text) {
        var tokens = NAMESPACE_TOKENS.get(bill.getNamespace());
        if (tokens == null) return 0f;
        float sumW = 0f, matchW = 0f;
        for (var e : tokens.entrySet()) {
            sumW += Math.abs(e.getValue());
            if (text.contains(e.getKey())) {
                matchW += e.getValue();
            }
        }
        return sumW == 0f
            ? 0f
            : Math.min(1f, Math.max(0f, matchW / sumW));
    }

    private float scoreDate(Bill bill, String text) {
        LocalDate dt = bill.getIntroducedDate();
        if (dt == null) return 0f;
        for (var fmt : DATE_FORMATTERS) {
            if (text.contains(dt.format(fmt).toLowerCase(Locale.ROOT))) {
                return 1f;
            }
        }
        return 0f;
    }

    /**
     * Scores URL-based signals: federal vs. state-level.
     */
    private float scoreUrl(Bill bill, String url) {
        String u = url.toLowerCase(Locale.ROOT);
        // Federal site override
        for (String site : FEDERAL_SITES) {
            if (u.contains(site)) {
                return bill.getNamespace() == LegislativeNamespace.US_CONGRESS ? 1f : 0f;
            }
        }
        // State site indicators
        for (StateInfo state : STATE_INFOS) {
            for (String indicator : state.getSiteIndicators()) {
                if (u.contains(indicator)) {
                    return bill.getNamespace() == LegislativeNamespace.US_CONGRESS ? 0f : 1f;
                }
            }
        }
        // neutral
        return 0.5f;
    }

    private boolean contains(String text, String term) {
        return term != null && !term.isEmpty() && text.contains(term.toLowerCase(Locale.ROOT));
    }
}

