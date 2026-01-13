package us.poliscore.bill;

import java.util.regex.Pattern;

import com.openai.models.ReasoningEffort;

import us.poliscore.ai.OpenAIModel;
import us.poliscore.model.bill.Bill;

/**
 * Routes bills to an interpretation model + reasoning effort.
 *
 * Philosophy:
 *  - Default to the stronger model (GPT-5) unless we can confidently classify a bill as "easy".
 *  - "Easy" means ceremonial/commemorative/naming/designation style bills that are unlikely to
 *    contain substantive statutory changes, enforcement, penalties, appropriations, elections, etc.
 *
 * This is intentionally high-precision / low-recall: it should only route to MINI when very safe.
 */
public class BillInterpretationRouter {

    // -------------------------
    // "Easy" classification
    // -------------------------
	
	/**
	 * At what length do we consider a bill to be "long" (i.e. 'not easy')
	 */
	public static final int LONG_BILL_THRESHOLD = 10_000;
	
	/**
	 * Extra title-level "not easy" signals.
	 * These catch a lot of substantive bills that don't use your HARD_TRIGGERS words
	 * (e.g., "authorize", "establish", "require") but might still contain an "honor/recognize"
	 * phrase somewhere in the title.
	 */
	private static final Pattern TITLE_HARD_STOPS = Pattern.compile(
	    "(?i)\\b(" +
	        // substantive verbs / mandates
	        "establish|authorize|reauthorize|require|prohibit|regulate|provide\\s+for|direct|create|" +
	        "expand|extend|amend|repeal|implement|enforce|modernize|improve|strengthen|" +
	        // programs / funding / administration
	        "program|grant|pilot|study|report|commission|task\\s+force|advisory|standards|rules|" +
	        // common "multi-purpose" / catch-all phrasing
	        "and\\s+for\\s+other\\s+purposes|for\\s+other\\s+purposes|and\\s+to\\s+provide\\s+for" +
	    ")\\b"
	);

	/**
	 * High-precision ceremonial patterns.
	 *
	 * We only accept these *specific shapes*, instead of "any ceremonial verb + any ceremonial noun anywhere".
	 * This avoids false positives like: "To recognize X ... and to establish a program for Federal facilities..."
	 */
//	private static final Pattern EASY_TITLE = Pattern.compile(
//	    "(?i)\\b(?:to|a\\s+bill\\s+to|relating\\s+to|a\\s+resolution|resolution)\\b\\s*.*?\\b(" +
//
//	        // 1) Naming/designation of a facility/object as a named thing (very common for post offices)
//	        "(?:designat(?:e|es|ed|ing)|name(?:s|d|ing)?|rename(?:s|d|ing)?)\\b.*?\\bas\\s+(?:the\\s+)?[\"'“”‘’]?.+?[\"'“”‘’]?\\s*\\b" +
//	            "(?:post\\s+office|highway|bridge|building|courthouse|federal\\s+building|park|memorial)\\b" +
//
//	        "|" +
//
//	        // 2) "Post Office" naming/designation without explicit "as the ..." phrasing
//	        "(?:designat(?:e|es|ed|ing)|name(?:s|d|ing)?|rename(?:s|d|ing)?)\\b.*?\\bpost\\s+office\\b" +
//
//	        "|" +
//
//	        // 3) Day/week/month designations (keep this strict)
//	        "designat(?:e|es|ed|ing)\\b.*?\\b(?:day|week|month)\\b" +
//
//	        "|" +
//
//	        // 4) Pure resolutions honoring/recognizing/commending (non-binding)
//	        "(?:a\\s+resolution|resolution)\\b.*?\\b(?:honor(?:s|ed|ing)?|recogniz(?:e|es|ed|ing)|commend(?:s|ed|ing)?|congratulat(?:e|es|ed|ing)|memorial(?:ize|izes|ized|izing))\\b" +
//
//	    ")"
//	);

	private static final Pattern EASY_TITLE = Pattern.compile(
		    "(?i)\\b(" +
		        // common bill openers (optional but helpful)
		        "(?:to|a\\s+bill\\s+to|relating\\s+to|a\\s+resolution|resolution)?\\s*" +

		        // core ceremonial verbs (this is the real signal)
		        "(?:designat(?:e|es|ed|ing)|name(?:s|d|ing)?|rename(?:s|d|ing)?|" +
		        "honor(?:s|ed|ing)?|recogniz(?:e|es|ed|ing)|commend(?:s|ed|ing)?|" +
		        "congratulat(?:e|es|ed|ing)|memorial(?:ize|izes|ized|izing))" +

		    ")\\b"
		);


    /**
     * Hard-stop triggers: if any appear (title or text), the bill is NOT "easy".
     * These are the short-but-scary cases: penalties, elections, appropriations, enforcement, etc.
     */
    private static final Pattern HARD_TRIGGERS = Pattern.compile(
        "(?i)\\b(" +
            // criminal / penalties
            "felony|misdemeanor|offense|penalt(y|ies)|punishable|imprison|incarcerat|jail|crime|" +
            // elections
            "election|voter|ballot|campaign|redistrict|" +
            // money / taxes
            "appropriat(e|ion)|tax|fee|revenue|bond|fund(s)?|budget|fiscal|" +
            // enforcement / liability
            "cause of action|civil (liability|penalt(y|ies))|enforce(ment)?|attorney general|injunct(ion)?|" +
            // statutory surgery signals
            "amend|repeal" +
        ")\\b"
    );

    /**
     * Structure markers are a rough proxy for how "statutory" and cross-referential a bill is.
     * Even ceremonial bills can be long, but "real law" usually shows up here.
     */
    private static final Pattern STRUCTURE_MARKERS = Pattern.compile(
        "(?i)\\b(section|sec\\.|subsection|chapter|subchapter)\\b"
    );

    // -------------------------
    // Routing thresholds
    // -------------------------

    /** If a bill has >= this many structure markers, it is considered not-easy. */
    private final int structureMarkerLimit;

    /** Optional: if bill is very far along, route to GPT-5 even if it looks easy. */
    private final float progressOverrideThreshold;

    /**
     * Create a router with conservative defaults.
     *
     * @param minEffort minimum effort the caller is willing to allow (LOW/MEDIUM/HIGH).
     */
    public BillInterpretationRouter() {
        this(6, 0.2f);
    }

    public BillInterpretationRouter(int structureMarkerLimit, float progressOverrideThreshold) {
        this.structureMarkerLimit = structureMarkerLimit;
        this.progressOverrideThreshold = progressOverrideThreshold;
    }

    // -------------------------
    // Public API
    // -------------------------

    public void route(InterpretationRequest request, OpenAIModel defaultModel, OpenAIModel miniModel, Bill bill, String billText) {
        request.setRequestedModel(chooseModel(defaultModel, miniModel, bill, billText));
        request.setReasoningEffort(chooseEffort(request, bill, billText));
    }

    public OpenAIModel chooseModel(OpenAIModel defaultModel, OpenAIModel miniModel, Bill bill, String billText) {
        return isEasyBill(bill, billText) ? miniModel : defaultModel;
    }
    
    public ReasoningEffort chooseEffort(InterpretationRequest req, Bill b, String billText) {
    	if (req.getRequestedModel().getId().toLowerCase().contains("mini"))
    		return ReasoningEffort.MEDIUM;
    	
		if (b.getStatus().getProgress() > progressOverrideThreshold || billText.length() > LONG_BILL_THRESHOLD)
			return ReasoningEffort.MEDIUM;
		else
			return ReasoningEffort.LOW;
	}

    // -------------------------
    // Easy bill detection
    // -------------------------


	public boolean isEasyBill(Bill bill, String billText) {
	    String title = bill.getName(); // keep original case; regex is (?i) anyway
	    String text = billText == null ? "" : billText;
	
	    // 0) Null/empty title => not easy
	    if (title == null || title.isBlank()) return false;
	    
	    // 1) Bill is long = Not easy
	    if (billText.length() > LONG_BILL_THRESHOLD) return false;
	
	    // 2) Title must match high-precision ceremonial shapes
	    boolean easyByTitle = EASY_TITLE.matcher(title).find();
	    if (!easyByTitle) return false;
	
	    // 2.5) Title-level hard stops (extra safety)
	    if (TITLE_HARD_STOPS.matcher(title).find()) return false;
	
	    // 3) Must NOT contain any hard triggers in title or text 
	    String combined = title + "\n" + text;
	    if (HARD_TRIGGERS.matcher(combined).find()) return false;
	
	    // 4) Must be structurally simple
//	    int structureHits = countMatches(STRUCTURE_MARKERS, text);
//	    if (structureHits >= structureMarkerLimit) return false;
	
	    // 4) Optional progress override: if it's really moving, be conservative
	    if (bill.getStatus().getProgress() > progressOverrideThreshold) return false;
	
	    return true;
	}

    // -------------------------
    // Helpers / adapters
    // -------------------------

    private static int countMatches(Pattern pattern, String s) {
        int count = 0;
        var m = pattern.matcher(s);
        while (m.find()) count++;
        return count;
    }

    private static String lowerOrNull(String s) {
        return s == null ? null : s.toLowerCase();
    }
}
