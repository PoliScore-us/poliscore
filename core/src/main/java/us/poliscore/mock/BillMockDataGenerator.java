package us.poliscore.mock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import us.poliscore.PoliscoreDataset;
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.IssueStats;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillStatus;
import us.poliscore.model.legislator.Legislator;

public final class BillMockDataGenerator {

    private static final String[] TITLES = {
        "Infrastructure Safety and Modernization Act",
        "Clean Air and Water Enhancement",
        "Small Business Innovation Support",
        "K–12 Teacher Support and Retention",
        "Cybersecurity and Critical Systems Protection",
        "Housing Affordability and Zoning Reform",
        "Veterans Care and Community Services",
        "Wildfire Mitigation and Forest Health",
        "Public Transit Reliability Initiative",
        "Opioid Response and Recovery Act"
    };

    private static final LegislativeChamber[] CHAMBERS = {
        LegislativeChamber.LOWER, LegislativeChamber.UPPER
    };

    private final Random rnd;
    private PoliscoreDataset dataset;

    /** Time-based seed. */
    public BillMockDataGenerator(PoliscoreDataset dataset) {
        this(dataset, System.currentTimeMillis());
    }

    /** Seeded for deterministic tests. */
    public BillMockDataGenerator(PoliscoreDataset dataset, long seed) {
        this.dataset = dataset;
        this.rnd = new Random(seed);
    }

    /** Generate N bills for a state/session namespace. */
    public List<Bill> generate(int count) {
        List<Bill> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(one());
        return out;
    }

    // ---- internals ----

    private Bill one() {
        var bill = new Bill();

        // Type + number
        var chamber = pick(CHAMBERS);
        String type = chamber == LegislativeChamber.UPPER ? "SB" : "HB";
        int number = 10 + rnd.nextInt(990);
        
        bill.setType(type);
        bill.setNumber(number);
    	bill.setId(Bill.generateId(dataset.getNamespace(), dataset.getSession().getCode(), bill.getType(), bill.getNumber()));
        
        bill.setOriginatingChamber(chamber);

        // Dates
        LocalDate intro = LocalDate.now().minusDays(30 + rnd.nextInt(300));
        LocalDate last  = intro.plusDays(rnd.nextInt(120));
        bill.setIntroducedDate(intro);
        bill.setLastActionDate(last);

        // Status (try for something with progress; fall back gracefully)
        bill.setStatus(pickStatus());

        // Name/URLs/ids (name may be overwritten by interpretation title in setInterpretation)
        String baseTitle = pick(TITLES);
        bill.setName(type + " " + number + " — " + baseTitle);
        bill.setOfficialUrl(mockOfficialUrl(dataset, chamber, type, number));
        bill.setLegiscanId(100000 + rnd.nextInt(900000));

        // Sponsor + a couple of cosponsors (mocked Legislator ids/names)
        bill.setSponsor(randomSponsor(chamber));
        int cos = rnd.nextInt(4); // 0–3
        for (int i = 0; i < cos; i++) bill.getCosponsors().add(randomSponsor(chamber));

        // Interpretation (includes IssueStats + rating + generated title)
        attachInterpretation(bill, baseTitle);

        return bill;
    }

    private void attachInterpretation(Bill bill, String seedTitle) {
        // Build IssueStats with plausible distribution
        IssueStats stats = MockDatasetUtil.issueStats(rnd);

        // Derive rating from OverallBenefitToSociety
        int obs = stats.getStat(TrackedIssue.OverallBenefitToSociety);
        int rating = clamp(Math.round(obs), -100, 100);

        // Metadata (per your instruction)
        var meta = AIInterpretationMetadata.construct("mock", "mockAiModel", 0, false);

        // Create the interpretation; set the fields we know Bill relies on
        var interp = new BillInterpretation();
        interp.setMetadata(meta);
        interp.setIssueStats(stats);
        interp.setRating(rating);

        // A generated title that can replace the placeholder name (Bill.setInterpretation handles swap)
        String genTitle = seedTitle + " (" + bill.getType() + " " + bill.getNumber() + ", " + dataset.getKey() + ")";
        interp.setGenBillTitle(genTitle);

        // Explanations / reasoning / references
        String shortX = "Estimated " + (rating >= 0 ? "positive" : "negative")
                + " net impact with moderate scope and " + bill.getCosponsors().size() + " cosponsor(s).";
        String longX = shortX + " This mock interpretation is heuristic: scores were sampled per issue and"
                + " combined into a normalized rating; not based on real text.";
        interp.setShortExplain(shortX);
        interp.setLongExplain(longX);
        interp.setReasoning("Mock generator: per-issue stats sampled; rating = normalized OverallBenefitToSociety.");

        bill.setInterpretation(interp);
    }

    private Bill.BillSponsor randomSponsor(LegislativeChamber chamber) {
        // make a plausible Legislator id & name (mirrors your Legislator.generateId pattern)
        String first = pick(FIRST_NAMES);
        String last  = pick(LAST_NAMES);
        var name = new Legislator.LegislatorName(first, last, first + " " + last);

        String chamberCode = chamber == LegislativeChamber.UPPER ? "S" : "H";
        String district = chamber == LegislativeChamber.UPPER ? String.valueOf(1 + rnd.nextInt(35))
                : (rnd.nextDouble() < 0.2 ? "At-Large" : String.valueOf(1 + rnd.nextInt(99)));
        String legislatorCode = (last + "-" + dataset.getKey() + "-" + chamberCode + "-" + district)
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "");

        String legId = Legislator.generateId(dataset.getNamespace(), dataset.getKey(), legislatorCode);
        var sponsor = new Bill.BillSponsor(legId, null, name);
        return sponsor;
    }

    private BillStatus pickStatus() {
        // 20% LAW/ENACTED, 30% PASSED_BOTH/ENROLLED, else INTRODUCED/IN_COMMITTEE
        double r = rnd.nextDouble();
        if (r < 0.20) {
            return new BillStatus("LAW", 1.0f, "LAW");
        } else if (r < 0.50) {
        	return new BillStatus("PASSED_BOTH", 0.8F, "PASSED_BOTH");
        } else {
            return new BillStatus("INTRODUCED", 0.1f, "INTRODUCED");
        }
    }

    private String mockOfficialUrl(PoliscoreDataset dataset, LegislativeChamber chamber, String type, int number) {
        String ch = (chamber == LegislativeChamber.UPPER ? "senate" : "house");
        return "https://leg." + dataset.getKey().toLowerCase(Locale.ROOT) + ".gov/" + ch + "/bills/" + type.toLowerCase(Locale.ROOT) + "/" + number;
    }

    // ---- helpers ----

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private String pick(String[] arr) { return arr[rnd.nextInt(arr.length)]; }
    private <T> T pick(T[] arr) { return arr[rnd.nextInt(arr.length)]; }

    private static final String[] FIRST_NAMES = {
        "Alex","Jordan","Taylor","Morgan","Casey","Riley","Avery","Quinn","Reese","Parker",
        "Logan","Hayden","Rowan","Skyler","Cameron","Harper","Emerson","Finley","Sage","Elliot"
    };
    private static final String[] LAST_NAMES = {
        "Anderson","Bennett","Campbell","Diaz","Edwards","Foster","Garcia","Henderson","Ibrahim","Jackson",
        "Kim","Lopez","Mitchell","Nguyen","OConnor","Patel","Quinn","Reynolds","Smith","Thompson",
        "Underwood","Vasquez","Williams","Xu","Young","Zhang"
    };

    // quick smoke test
    public static void main(String[] args) {
    	var dataset = MockDatasetUtil.mockDataset();
    	
        var gen = new BillMockDataGenerator(dataset, 42L);
        var sample = gen.generate(5);
        for (Bill b : sample) {
            System.out.println(b.getType() + " " + b.getNumber() + " | rating=" + b.getRating()
                    + " | impact=" + b.getImpact()
                    + " | title=" + b.getShortName());
        }
    }
}

