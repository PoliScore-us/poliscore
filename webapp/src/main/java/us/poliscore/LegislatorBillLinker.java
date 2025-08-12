package us.poliscore;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import io.quarkus.logging.Log;
import lombok.val;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorBillInteraction;

public class LegislatorBillLinker {
	public static void linkInterpBills(Legislator leg, List<LegislativeSession> sessions) {
	    try {
	        // Grab the text to process
	        String exp = leg.getInterpretation().getLongExplain();
	        if (exp == null || exp.isBlank()) {
	            return; // Nothing to link
	        }
	        
	        // If this prompt has hardcoded bill references then we can't also run a name linker because it could double link them.
	        if (exp.contains("[BIL/") || exp.contains("(BIL/")) {
                String linked = linkMarkdownBills(exp, sessions);
                leg.getInterpretation().setLongExplain(linked);
                return;
            }

	        // 1. Sort interactions by descending length of billName
	        List<LegislatorBillInteraction> sortedInteractions = leg.getInteractions().stream()
	                .sorted(Comparator
	                        .comparingInt((LegislatorBillInteraction b) -> b.getBillName().length())
	                        .reversed())
	                .collect(Collectors.toList());

	        // We'll build a single "mega-pattern" that captures any of the known names/IDs.
	        // Also store a map from matched-lowercase-string -> LinkRef (url + link text).
	        StringBuilder patternBuilder = new StringBuilder("(?iu)\\b(");
	        Map<String, LinkRef> dictionary = new HashMap<>();

	        boolean first = true;
	        for (LegislatorBillInteraction interact : sortedInteractions) {
	            // 2. Normalize the official bill name
	            String rawBillName = interact.getBillName();
	            String normalizedName = normalizeBillName(rawBillName);

	            // 3. Build the link URL
	            String url = linkForBill(interact.getBillId(), sessions);

	            // 4. Construct a readable form of the bill ID (e.g. "H.R.-1234" or "S.-201")
	            String typeName = interact.getBillId().split("/")[4]; // e.g. "H.R."
	            String billNumber = interact.getBillId().split("/")[5];       // e.g. "1234"
	            String altBillId = typeName + "-" + billNumber;

	            // 5. Register both the normalized bill name and the altBillId in our dictionary
	            //    so that either form triggers the same replacement.
	            dictionary.put(normalizedName.toLowerCase(), new LinkRef(url, normalizedName));
	            dictionary.put(altBillId.toLowerCase(), new LinkRef(url, normalizedName));

	            // 6. Add both forms to the big capturing group (quoted so special chars are safe).
	            if (!first) {
	                patternBuilder.append("|");
	            }
	            patternBuilder
	                    .append(Pattern.quote(normalizedName))
	                    .append("|")
	                    .append(Pattern.quote(altBillId));
	            first = false;
	        }
	        patternBuilder.append(")\\b");  // close the group + a word boundary

	        // 7. Compile the combined pattern (case-insensitive, unicode)
	        Pattern combinedPattern = Pattern.compile(patternBuilder.toString(),
	                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	        Matcher matcher = combinedPattern.matcher(exp);

	        // 8. Single-pass replacement
	        StringBuffer sb = new StringBuffer();
	        while (matcher.find()) {
	            // The text that matched one of our known bill name/id variants
	            String matchedText = matcher.group(1);
	            LinkRef linkRef = dictionary.get(matchedText.toLowerCase());

	            if (linkRef != null) {
	                // Build the <a> tag using the dictionary info
	                String replacement = "<a href=\"" + linkRef.url + "\">" + linkRef.linkText + "</a>";
	                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
	            } else {
	                // If for some reason we can't find it, just re-insert the raw text
	                matcher.appendReplacement(sb, Matcher.quoteReplacement(matchedText));
	            }
	        }
	        matcher.appendTail(sb);

	        // 9. Update the legislator’s interpretation text
	        leg.getInterpretation().setLongExplain(sb.toString());

	    } catch (Throwable t) {
	        Log.error(t);
	    }
	}
	
	/**
	 * Converts new markdown bill references into HTML anchors using linkForBill.
	 * Supports:
	 *   [Some Label](BIL/...)
	 *   [Some Label][BIL/...]
	 */
	private static final Pattern BILL_LINK = Pattern.compile(
	    "\\[(?<label>[^\\]]+)]\\s*(?:\\((?<id1>BIL/[^)]+)\\)|\\[(?<id2>BIL[^\\]]+)])",
	    Pattern.CASE_INSENSITIVE // allow bil/ too
	);

	private static String linkMarkdownBills(String text, List<LegislativeSession> sessions) {
	    Matcher m = BILL_LINK.matcher(text);
	    StringBuffer out = new StringBuffer();
	    while (m.find()) {
	        String label = m.group("label");
	        String id = m.group("id1") != null ? m.group("id1") : m.group("id2"); // includes the BIL/... prefix
	        String href = safeLinkForBill(id, sessions);

	        // Only replace when we have a real URL (not one that still contains "BIL/")
	        String replacement = (href != null && !href.contains("BIL/"))
	                ? "<a href=\"" + href + "\">" + label + "</a>"
	                : m.group(0);

	        m.appendReplacement(out, Matcher.quoteReplacement(replacement));
	    }
	    m.appendTail(out);
	    return out.toString();
	}
    
    private static String prettyLabelFromId(String id) {
        try {
            String[] parts = id.split("/");
            String typeName = parts.length > 4 ? parts[4] : "";
            String billNumber = parts.length > 5 ? parts[5] : "";
            String label = (typeName + "-" + billNumber).replaceAll("^-|-$", "");
            return label.isBlank() ? id : label;
        } catch (Exception e) {
            return id;
        }
    }

    private static String safeLinkForBill(String id, List<LegislativeSession> sessions) {
        try {
            return linkForBill(id, sessions);
        } catch (Exception e) {
            Log.warn("Failed to resolve bill id from markdown: " + id, e);
            return null;
        }
    }
	
	public static String linkForBill(String id, List<LegislativeSession> sessions)
	{
		int year = LocalDate.now().getYear();
		
		val namespace = LegislativeNamespace.of(id.split("/")[1] + "/" + id.split("/")[2]);
		if (namespace.equals(LegislativeNamespace.US_CONGRESS)) {
			val sessionCode = Integer.valueOf(id.split("/")[3]);
			year = (sessionCode - 1) * 2 + 1789 + 1;
			
			return "/" + year + "/bill/" + id.substring(StringUtils.ordinalIndexOf(id, "/", 4) + 1);
		} else {
			val session = sessions.stream().filter(s -> s.getCode().equals(id.split("/")[3])).findAny().get();
			year = session.getEndDate().getYear();
			
			return "/" + year + "/" + id.split("/")[2] + "/bill/" + id.substring(StringUtils.ordinalIndexOf(id, "/", 4) + 1);
		}
	}

	/**
	 * A simple class to hold both the final link URL and the link display text.
	 */
	private static class LinkRef {
	    final String url;
	    final String linkText;
	    LinkRef(String url, String linkText) {
	        this.url = url;
	        this.linkText = linkText;
	    }
	}

	/**
	 * Strips trailing periods, trims whitespace, etc., to keep the text consistent.
	 */
	public static String normalizeBillName(String name) {
	    if (name == null) return "";
	    name = name.strip();
	    while (name.endsWith(".")) {
	        name = name.substring(0, name.length() - 1).strip();
	    }
	    return name;
	}

}
