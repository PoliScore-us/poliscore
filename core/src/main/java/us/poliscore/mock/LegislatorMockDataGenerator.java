package us.poliscore.mock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import us.poliscore.PoliscoreDataset;
import us.poliscore.legiscan.view.LegiscanState;
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.Party;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorInterpretation;

public final class LegislatorMockDataGenerator {

    private static final String[] FIRST_NAMES = {
        "Alex","Jordan","Taylor","Morgan","Casey","Riley","Avery","Quinn","Reese","Parker",
        "Logan","Hayden","Rowan","Skyler","Cameron","Harper","Emerson","Finley","Sage","Elliot"
    };

    private static final String[] LAST_NAMES = {
        "Anderson","Bennett","Campbell","Diaz","Edwards","Foster","Garcia","Henderson","Ibrahim","Jackson",
        "Kim","Lopez","Mitchell","Nguyen","O'Connor","Patel","Quinn","Reynolds","Smith","Thompson",
        "Underwood","Vasquez","Williams","Xu","Young","Zhang"
    };

    private static final Party[] PARTIES = { Party.DEMOCRAT, Party.REPUBLICAN, Party.INDEPENDENT };
    private static final LegislativeChamber[] CHAMBERS = { LegislativeChamber.LOWER, LegislativeChamber.UPPER };

    private final Random rnd;
    protected PoliscoreDataset dataset;

    /** Uses a time-based seed. */
    public LegislatorMockDataGenerator(PoliscoreDataset dataset) {
    	this(dataset, System.currentTimeMillis());
    }

    /** Seeded for deterministic tests. */
    public LegislatorMockDataGenerator(PoliscoreDataset dataset, long seed) {
        this.rnd = new Random(seed);
        this.dataset = dataset;
    }

    /**
     * Generate a list of mock legislators for a given state.
     * @param count how many legislators to create
     * @param state LegiscanState (e.g., CO, CA)
     */
    public List<Legislator> generate(int count) {
        List<Legislator> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(one(i));
        }
        return list;
    }

    // ---- internals ----

    private Legislator one(int index) {
    	var state = dataset.getNamespace().toState();
        var leg = new Legislator();
        
        // Name
        String first = pick(FIRST_NAMES);
        String last  = pick(LAST_NAMES);
        var name = new Legislator.LegislatorName(first, last, first + " " + last);
        leg.setName(name);

        // Birthday (between 1950-01-01 and 1995-12-31)
        leg.setBirthday(randomBirthday(1950, 1995));

        // LegiScan-ish numeric id (mock)
        leg.setLegiscanId(10_000 + rnd.nextInt(90_000));
        leg.setId(Legislator.generateId(dataset.getNamespace(), dataset.getRegularSession().getCode(), String.valueOf(leg.getLegiscanId())));

        // Chamber/party/district
        var chamber = pick(CHAMBERS);
        var party   = pick(PARTIES);
        String district = makeDistrict(chamber);

        // Terms: create 1–2 terms that cover roughly the last 6–10 years
        var terms = new Legislator.LegislatorLegislativeTermSortedSet();
        int numTerms = 1 + rnd.nextInt(2);
        LocalDate start = LocalDate.now().minusYears(6 + rnd.nextInt(5)).withMonth(1).withDayOfMonth(1);
        for (int t = 0; t < numTerms; t++) {
            LocalDate termStart = start.plusYears(t * 2L);
            LocalDate termEnd   = termStart.plusYears(2);
            terms.add(new Legislator.LegislativeTerm(termStart, termEnd, state, district, party, chamber));
        }
        leg.setTerms(terms);

        // Official URL (mocked)
        leg.setOfficialUrl(mockOfficialUrl(chamber, district, last));

        // Impact map (coarse random numbers; OverallBenefitToSociety positive-biased)
        for (TrackedIssue issue : TrackedIssue.values()) {
            long val = issue == TrackedIssue.OverallBenefitToSociety
                    ? 50 + rnd.nextInt(950)
                    : rnd.nextBoolean() ? rnd.nextInt(400) : -rnd.nextInt(400);
            leg.getImpactMap().put(issue, val);
        }

        // Interactions left empty by default (can be filled by caller if desired)

        attachInterpretation(leg);

        return leg;
    }

    private String pick(String[] arr) {
        return arr[rnd.nextInt(arr.length)];
    }

    private <T> T pick(T[] arr) {
        return arr[rnd.nextInt(arr.length)];
    }
    
    private void attachInterpretation(Legislator leg) {
        // Build a stable-ish legislatorCode for the interpretation id
        var lastTerm = leg.getTerms().last();
        String chamberCode = lastTerm.getChamber() == LegislativeChamber.UPPER ? "S" : "H";
        String districtSlug = (lastTerm.getDistrict() == null ? "AL" : lastTerm.getDistrict()).replaceAll("[^A-Za-z0-9]+", "-");
        String legislatorCode = (leg.getName().getLast() + "-" + lastTerm.getState().name() + "-" + chamberCode + "-" + districtSlug)
                .toUpperCase(Locale.ROOT);

        var meta = AIInterpretationMetadata.construct("mock", "mockAiModel", 0, false);

        // Build interpretation with minimal required fields
        var interp = new LegislatorInterpretation(dataset.getNamespace(), "test", legislatorCode, meta, MockDatasetUtil.issueStats(rnd));

        // Derive a 0..100 rating from OverallBenefitToSociety impact (soft clamp)
        long obs = leg.getImpact(TrackedIssue.OverallBenefitToSociety);
        int rating = Math.clamp((int)Math.round( (obs / 10.0) ), -100, 100);
        // Shift to 0..100 if your UI expects that; keeping -100..100 since Legislator uses abs() variants.
        interp.setRating(rating);

        // Lightweight explanations
        String summary = String.format(
            "%s shows a net %s impact with recent activity in %s (%s %s).",
            leg.getName().getOfficial_full(),
            rating >= 0 ? "positive" : "negative",
            lastTerm.getState().name(),
            lastTerm.getChamber(),
            lastTerm.getDistrict() == null ? "AL" : lastTerm.getDistrict()
        );
        interp.setShortExplain(summary);
        interp.setLongExplain(summary + " This mock interpretation was auto-generated for test purposes.");
        interp.setReasoning("Heuristic mock: rating derived from OverallBenefitToSociety impact and recent term metadata.");
        interp.setReferences("mock://generated");

        // Optional: lightweight hash
        interp.setHash((leg.getName().getOfficial_full() + "|" + legislatorCode).hashCode());

        // Attach
        leg.setInterpretation(interp);
    }

    private LocalDate randomBirthday(int minYear, int maxYear) {
        int year  = minYear + rnd.nextInt((maxYear - minYear) + 1);
        int month = 1 + rnd.nextInt(12);
        int day   = Math.min(28, 1 + rnd.nextInt(28)); // keep it simple; avoids invalid dates
        return LocalDate.of(year, month, day);
    }

    private String makeDistrict(LegislativeChamber chamber) {
        // Senate often district-numbered; House could be district or at-large
        if (chamber == LegislativeChamber.UPPER) {
            return String.valueOf(1 + rnd.nextInt(35));
        }
        // 20% at-large, otherwise numeric
        return rnd.nextDouble() < 0.2 ? "At-Large" : String.valueOf(1 + rnd.nextInt(99));
    }

    private String chamberPrefix(LegislativeChamber chamber) {
        return (chamber == LegislativeChamber.UPPER) ? "S" : "H";
    }

    private String mockOfficialUrl(LegislativeChamber chamber, String district, String lastName) {
        String ch = chamber == LegislativeChamber.UPPER ? "senate" : "house";
        String dist = district == null ? "" : ("/" + district.toLowerCase(Locale.ROOT).replace(' ', '-'));
        return "https://leg." + dataset.getKey().toLowerCase(Locale.ROOT) + ".gov/" + ch + dist + "/" + lastName.toLowerCase(Locale.ROOT);
    }

    // ---- quick demo main (optional) ----
    public static void main(String[] args) {
    	var dataset = MockDatasetUtil.mockDataset();
    	
        var gen = new LegislatorMockDataGenerator(dataset, 42L);
        List<Legislator> sample = gen.generate(5);
        // no I/O; put a breakpoint here or print minimal info for a smoke test:
        for (var l : sample) {
            System.out.println(l.getName().getOfficial_full() + " (" + l.getParty() + ", " +
                               l.getTerms().last().getState() + " " +
                               l.getTerms().last().getChamber() + " " +
                               l.getTerms().last().getDistrict() + ") " +
                               "DOB=" + l.getDate() + " Impact=" + l.getImpact());
        }
    }
}

