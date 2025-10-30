package us.poliscore.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ParsingUtil {
	// --- Tunable thresholds (conservative defaults) ---
    /** Minimum candidate length to consider (very tiny blocks are usually noise). */
    private static final int MIN_CANDIDATE_LEN = 8;

    /** Maximum candidate length to extract (avoid huge captures on pathological input). */
    private static final int MAX_CANDIDATE_LEN = 100_000;

    /** Structural character density threshold: proportion of {}[]:," whitespace to total. */
    private static final double MIN_STRUCTURAL_DENSITY = 0.12;

    /** How many key:value-like hits are enough to say "likely JSON". */
    private static final int MIN_KEY_VALUE_HITS = 1;

    // --- Regex signals ---
    // "key" : value   (value may start with quote, digit, sign, {, [, t/f/n)
    private static final Pattern KEY_VALUE_REGEX = Pattern.compile(
            "\"[^\"\\\\\\r\\n]{1,100}\"\\s*:\\s*(\"|\\{|\\[|true\\b|false\\b|null\\b|-?\\d)"
    );

    // Quoted key (appears anywhere)
    private static final Pattern QUOTED_KEY_REGEX = Pattern.compile("\"[^\"]{1,100}\"\\s*:");

    // Array-ish quick check (e.g., ["a", "b"] or [1,2] or [{...}, {...}])
    private static final Pattern ARRAY_LIKE_REGEX = Pattern.compile(
            "^\\s*\\[\\s*(\\{|\\[|\"|-?\\d|true\\b|false\\b|null\\b).+\\]\\s*$",
            Pattern.DOTALL
    );

    // Object-ish quick check
    private static final Pattern OBJECT_LIKE_REGEX = Pattern.compile(
            "^\\s*\\{.+\\}\\s*$",
            Pattern.DOTALL
    );

    /**
     * Returns {@code true} if the given text likely contains a JSON block somewhere inside.
     *
     * <p>Safe for large inputs; it scans once and evaluates bounded-size candidates.</p>
     */
    public static boolean containsJson(String text) {
        if (text == null) return false;

        // Fast prefilter: must contain an opening brace/bracket and a colon (very common in JSON)
        if (!containsAny(text, '{', '[', ':')) return false;

        for (String candidate : extractBraceBlocks(text)) {
            if (candidate.length() < MIN_CANDIDATE_LEN) continue;

            if (!OBJECT_LIKE_REGEX.matcher(candidate).find() &&
                !ARRAY_LIKE_REGEX.matcher(candidate).find()) {
                continue; // must at least look like an object/array wrapper
            }

            if (!hasReasonableStructuralDensity(candidate)) continue;

            // Strong signals: quoted keys with colon, and at least one plausible value form
            int kvHits = countMatches(KEY_VALUE_REGEX, candidate);
            boolean hasQuotedKey = QUOTED_KEY_REGEX.matcher(candidate).find();

            if (kvHits >= MIN_KEY_VALUE_HITS && hasQuotedKey) {
                return true; // confident enough
            }

            // Slightly looser fallback: objects with many commas/colons and some quoted text
            if (isLooselyJsonish(candidate)) {
                return true;
            }
        }

        return false;
    }

    // =========================
    // Implementation details
    // =========================

    private static boolean containsAny(String s, char a, char b, char c) {
        boolean ha = false, hb = false, hc = false;
        for (int i = 0, n = s.length(); i < n && !(ha && hb && hc); i++) {
            char ch = s.charAt(i);
            if (ch == a) ha = true;
            else if (ch == b) hb = true;
            else if (ch == c) hc = true;
        }
        return (ha || hb) && hc; // need ':' plus at least one opener
    }

    /**
     * Extracts brace/bracket balanced blocks, respecting quotes and escapes.
     * We start a block at any '{' or '[' and capture until its matching closer.
     */
    private static List<String> extractBraceBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        final int n = text.length();

        // Track whether we're inside a string and escaped
        boolean inString = false;
        char stringQuote = 0;
        boolean escaped = false;

        // Stack of (openingChar, startIndex)
        Deque<Open> stack = new ArrayDeque<>();

        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false; // consume escape
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == stringQuote) {
                    inString = false;
                }
                continue;
            }

            if (c == '"' || c == '\'') { // tolerate single quotes in surrounding text
                inString = true;
                stringQuote = c;
                escaped = false;
                continue;
            }

            if (c == '{' || c == '[') {
                stack.push(new Open(c, i));
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    Open open = stack.pop();
                    if (matches(open.ch, c)) {
                        // Only take top-level balanced segments (stack empty after pop)
                        if (stack.isEmpty()) {
                            int start = open.index;
                            int end = i + 1;
                            int len = end - start;
                            if (len >= MIN_CANDIDATE_LEN) {
                                // Clip overly huge blocks
                                if (len > MAX_CANDIDATE_LEN) {
                                    end = start + MAX_CANDIDATE_LEN;
                                }
                                blocks.add(text.substring(start, end));
                            }
                        }
                    } else {
                        // Mismatch: drop any accumulated opens to avoid cascading garbage
                        stack.clear();
                    }
                }
            }
        }

        return blocks;
    }

    private static boolean matches(char open, char close) {
        return (open == '{' && close == '}') || (open == '[' && close == ']');
    }

    private static int countMatches(Pattern p, CharSequence s) {
        var m = p.matcher(s);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private static boolean hasReasonableStructuralDensity(String s) {
        int structural = 0;
        int total = s.length();
        for (int i = 0; i < total; i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '{': case '}':
                case '[': case ']':
                case ':': case ',':
                case '"':
                case ' ': case '\n': case '\r': case '\t':
                    structural++;
                    break;
                default:
                    // ignore
            }
        }
        double density = (total == 0) ? 0.0 : (double) structural / total;
        return density >= MIN_STRUCTURAL_DENSITY;
    }

    private static boolean isLooselyJsonish(String s) {
        // Heuristic: lots of colons/commas + some quotes is often JSON-ish even if keys aren’t perfect
        int quotes = 0, colons = 0, commas = 0, braces = 0, brackets = 0;
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '"': quotes++; break;
                case ':': colons++; break;
                case ',': commas++; break;
                case '{': case '}': braces++; break;
                case '[': case ']': brackets++; break;
                default:  // ignore
            }
        }

        // Basic loose rules:
        // - at least one container
        // - at least one colon (key:value)
        // - either quoted content or array/object mix with commas
        boolean hasContainer = (braces >= 2) || (brackets >= 2);
        boolean hasKvHint = colons >= 1;
        boolean hasQuotedContent = quotes >= 2;
        boolean looksLikeList = commas >= 1 && (brackets >= 2 || braces >= 2);

        return hasContainer && hasKvHint && (hasQuotedContent || looksLikeList);
    }

    private record Open(char ch, int index) {}
}
