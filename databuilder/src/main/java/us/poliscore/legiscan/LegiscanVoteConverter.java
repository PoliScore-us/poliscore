package us.poliscore.legiscan;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import io.quarkus.logging.Log;
import us.poliscore.legiscan.view.LegiscanRollCallView;
import us.poliscore.legiscan.view.LegiscanVoteDetailView;
import us.poliscore.legiscan.view.LegiscanVoteStatus;
import us.poliscore.model.VoteStatus;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillVote;

public class LegiscanVoteConverter {
	private static final Set<String> REPORTED_UNCLASSIFIED_ROLL_CALL_DESCRIPTIONS = ConcurrentHashMap.newKeySet();
	
	public static LegislatorBillVote convert(LegiscanRollCallView rollCall, LegiscanVoteDetailView vote, Legislator leg, Bill bill) {
		if (isProcedural(rollCall.getDescription())) return null;
		
		if (!isBillVote(rollCall.getDescription())) {
			if (REPORTED_UNCLASSIFIED_ROLL_CALL_DESCRIPTIONS.add(rollCall.getDescription())) {
				Log.error("[" + rollCall.getDescription() + "] is not a whitelisted roll call description. Please verify that this description is not procedural and either whitelist or (procedural) blacklist it.");
			}
			return null;
		}
		
		LegislatorBillVote interaction = new LegislatorBillVote(toVoteStatus(vote.getVote()));
		interaction.setLegId(leg.getId());
		interaction.setBillId(bill.getId());
		interaction.setDate(rollCall.getDate());
		interaction.setBillName(bill.getName());
		interaction.setId(LegislatorBillVote.generateId(interaction.getLegId(), interaction.getDate(), interaction.getBillId()));
		
		return interaction;
	}
	
	/**
	 * Whitelisting means that a developer has explicitly reviewed the description and agrees that it is a yes or a no vote on a bill (not procedural).
	 * 
	 * @param rollCallDesc
	 */
	protected static boolean isBillVote(String rollCallDesc) {
	    String d = rollCallDesc.toLowerCase().trim();

	    if (isExplicitlySubstantive(d)) {
	        return true;
	    }

	    if (containsAny(d,
	    		// --- STRONG FINAL PASSAGE SIGNALS ---
	            "passage",
	            "adopt",
	            "third reading",
	            "overriding",
	            "override",
	            "on the joint resolution",
	            "on the resolution",
	            "read third time and passed",
	            "passed",
	            "on the concurrent resolution",
	            "on the resolution",
	            "adopted",
	            "house: resolutions res", // Used in colorado to vote on a resolution passage
	            
	            // --- CONFERENCE / CONCURRENCE (STILL SUBSTANTIVE) ---
	            "conference report agreed to",
	            "agree to the conference report",
	            "agreed to conference report",
	            "concur in senate amendment",
	            "concur in the senate amendment",
	            "agree to senate amendment",
	            "agree to the senate amendment",
	            "agreed to senate amendment",
	            
	            // --- FINAL ADOPTION (LESS COMMON WORDING VARIANTS) ---
	            "on adoption",
	            "adoption of the bill",
	            "bill adopted"
	    		)) {
	        return true;
	    }

	    // --- SUSPENSION (TRICKY BUT OFTEN USED FOR PASSAGE) ---
	    // Only whitelist if clearly tied to passage
	    if (d.contains("suspend the rules") && containsAny(d,
	            "and pass",
	            "and pass the bill",
	            "and agree",
	            "and concur")) {
	        return true;
	    }
	    
	    for (String regex : new String[] {
	    	"(?i)RV#\\d+", // RV#499 : A vote on a bill in texas and this is the exact id of the vote
	    }) {
	    	if (Pattern.compile(regex).matcher(d).find()) return true;
	    }

	    return false;
	}
	
	/**
	 * We consider "procedural" in this context to be anything that is NOT a yes or a no vote on a bill.
	 * 
	 * @param rollCallDesc
	 * @return
	 */
	protected static boolean isProcedural(String rollCallDesc) {
	    if (rollCallDesc == null || rollCallDesc.isBlank()) {
	        return true; // safest default
	    }

	    String d = rollCallDesc.toLowerCase().trim();

	    // These descriptions were explicitly reviewed as votes that advance or
	    // finally reconcile the bill, even though some contain words such as
	    // "amendment", "conference", "reading", or "suspend" that are otherwise
	    // treated conservatively as procedural.
	    if (isExplicitlySubstantive(d)) {
	        return false;
	    }

	    // Strong procedural indicators (high confidence)
	    if (containsAny(d,
	            "recommit",
	            "previous question",
	            "motion to table",
	            "committee",
	            "journal",
	            "statement",
	            "chair",
	            "reconsider",
	            "refer",
	            "veto lo",
	            "miscellaneous",
	            "veto received",
	            "recommendation",
	            "engross",
	            "judiciary", // A vote inside a senate committee
	            "concur in",
	            "house amdt",
	            "senate amdt",
	            "with amdt",
	            "consideration",
	            "lay over",
	            "until",
	            "laid before the house",
	            "consideration",
	            "veto message",
	            "strike",
	            "divisions",
	            "division",
	            "retain",
	            "instruct",
	            "conferee",
	            "memorial",
	            "reading",
	            "title",
	            "layover",
	            "show",
	            "waive",
	            "conference",
	            "motion to adjourn",
	            "motion to proceed",
	            "motion to reconsider",
	            "motion to refer",
	            "motion to discharge",
	            "motion to resolve debate",
	            "motion to withdraw and commit",
	            "motion to remove from the table",
	            "immediately transmit",
	            "motion to print",
	            "motion to suspend senate rule",
	            "remove from local cal",
	            "appeal of the ruling",
	            "motion to commit",
	            "motion to recommit",
	            "point of order",
	            "comm report",
	            "agreeing to the rule",
	            "on ordering the previous question",
	            "on consideration of",
	            "motion prevails",
	            "Motion fails",
	            "calendar",
	            "on the consideration of",
	            "on the amendment",
	            "rule suspended",
	            "amendment fails of",
	            "amended",
	            "amendment",
	            "amend",
	            "concurs",
	            "postponed",
	            "postpone",
	            "vote recorded",
	            "record vote",
	            "read 3rd time",
	            "read 2nd time",
	            "read 1st time",
	            "rules suspended",
	            "rule suspended",
	            "to the amendment",
	            "cloture",
	            "invoke cloture")) {
	        return true;
	    }

	    if (d.equals("table") || d.startsWith("table:")
	            || d.equals("adjournment") || d.startsWith("adjournment:")) {
	        return true;
	    }

	    // Weak procedural / context-dependent (still safer to exclude for now)
	    if (containsAny(d,
	            "suspend the rules",
	            "consideration of the bill",
	            "on agreeing to the resolution")) {
	        return true;
	    }
	    
	    for (String regex : new String[] {
	    	"(?i)^motion test$",
	    	"(?i)^(house|senate)\\s+[a-z&\\s]+:\\s*[a-z]\\.\\d+$",
	    	"(?i)\\b[FS]A\\d+\\b", // Contains SA1 (Senate Amendment #1) or FA1 (Floor Amendment #1)
	    	"(?i)\\bamd\\b", // Contains the word 'amd' (not inside another word)
	        "(?i)\\bmisc\\b", // Contains the word 'misc' (not inside another word)
	        "(?i)^am\\s+\\d+\\s+\\d+", // Georgia amendment number, e.g. "Am 62 0077"
	        "(?i)\\brecon\\b", // Georgia abbreviation for reconsideration
	        "(?i)^move from .+ to .+(?::|$)" // Move a bill between Georgia calendars/committees
	    }) {
	    	if (Pattern.compile(regex).matcher(d).find()) return true;
	    }

	    return false;
	}

	private static boolean isExplicitlySubstantive(String description) {
		if (containsAny(description,
				// Georgia votes agreeing to the other chamber's substitute or
				// amendment are final reconciliation actions on the bill.
				"agree to house substitute",
				"agree to senate substitute",
				"agree to senate sub as am",
				"agree to senate am as am",
				"agree to sam to hsub",
				"uncontested house resolutions",

				// Existing reviewed descriptions that otherwise collide with broad
				// procedural keywords below.
				"conference report agreed to",
				"agree to the conference report",
				"agreed to conference report",
				"concur in senate amendment",
				"concur in the senate amendment",
				"agree to senate amendment",
				"agree to the senate amendment",
				"agreed to senate amendment",
				"read third time and passed")) {
			return true;
		}

		return description.contains("suspend the rules") && containsAny(description,
				"and pass",
				"and pass the bill",
				"and agree",
				"and concur");
	}
	
	protected static VoteStatus toVoteStatus(LegiscanVoteStatus legiscanVoteStatus) {
	    if (legiscanVoteStatus == null) {
	        throw new IllegalArgumentException("LegiscanVoteStatus cannot be null.");
	    }

	    switch (legiscanVoteStatus) {
	        case YEA:
	            return VoteStatus.AYE;
	        case NAY:
	            return VoteStatus.NAY;
	        case ABSTAIN:
	            return VoteStatus.PRESENT;
	        case ABSENT:
	            return VoteStatus.NOT_VOTING;
	        default:
	            throw new IllegalStateException("Unexpected value: " + legiscanVoteStatus);
	    }
	}

	protected static boolean containsAny(String text, String... needles) {
		if (text == null) return false;
		
	    for (String needle : needles) {
	        if (text.contains(needle)) {
	            return true;
	        }
	    }
	    return false;
	}
}
