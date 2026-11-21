package us.poliscore.funding;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import us.poliscore.model.bill.FundingSnapshot.FundingRow;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.StringReader;
import java.io.IOException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import us.poliscore.parsing.BillTextPlaceholder;

/** Extract explicit appropriations only. Authorization ceilings and unpriced CR formulas have no row. */
public final class BillFunding {
    public record Line(String label, long amount, int year, String evidence) {}
    private static final Pattern ENACTING_TEXT = Pattern.compile("(?i)\\b(?:be\\s+it\\s+enacted|resolved(?:,|\\s+by\\b))");
    private static final Pattern FUNDING_CUE = Pattern.compile("(?i)\\p{Sc}|\\b(?:dollars?|cents?|USD|budget authority|"
            + "shall be appropriated|is appropriated|are appropriated|at the rate for operations)\\b");
    private static final String MONEY = "\\$([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?![0-9,.]\\d)";
    private static final Pattern GRANT = Pattern.compile("(?i)^For (?:necessary expenses|expenses(?:, not otherwise provided for,)? necessary|payments|the acquisition|salaries(?: and expenses)?|pay, allowances|compensation of officers|Department of Energy expenses)\\b[^$:]{0,3500}?[,;]\\s*" + MONEY + "(?!\\s+(?:thousand|million|billion|trillion)\\b)(?=[.,;:\\s]|$)");
    private static final Pattern YEAR = Pattern.compile("(?i)appropriations(?: and extensions)? act,?\\s+(20\\d{2})");
    private static final Pattern STATE_YEAR = Pattern.compile("(?i)fiscal year beginning July 1,?\\s+(20\\d{2})");
    private static final Pattern PART = Pattern.compile("(?im)^\\s*TOTALS PART [IVXLCDM]+\\s*\\(([A-Z\\s&'/-]{1,150})\\)\\d*\\s*" + MONEY);
    private static final Pattern STATE_CLAUSE = Pattern.compile("(?i)(?<![\\w$-])" + MONEY + " is appropriated to (?:the )?([a-z][a-z ,&'-]{2,120}?) for (?:use by |the |implementation|allocation|distribution)");
    private static final Pattern PLAIN_GRANT = Pattern.compile("(?im)^[ \\t]*([A-Za-z][A-Za-z &,'–—-]{2,90})[ \\t]*\\R(?:\\s*\\([^()]{1,80}\\)\\s*\\R)?\\s*(For (?:necessary expenses|expenses necessary|payments|salaries and expenses)\\b[^$:]{0,3500}?[,;]\\s*\\$[\\d,]+)(?!\\s+(?:thousand|million|billion|trillion)\\b)(?=[.,;:\\s]|$)");

    private static final Pattern ACCOUNT_LEVEL = Pattern.compile("^" + MONEY + " for ", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT_LIST = Pattern.compile(
            "(?i)^(?:notwithstanding section \\d+[^:]{0,120}, )?the levels? for (?:each of )?(?:the following[^:]{0,70} accounts|appropriations accounts)\\b");
    private static final Pattern QUOTED_ACCOUNT_AMOUNT = Pattern.compile("^,\\s*" + MONEY);
    private static final Pattern SINGLE_ACCOUNT = Pattern.compile("(?i)^notwithstanding section \\d+(?: of this Act)?, the level for ");
    private static final Pattern SINGLE_ACCOUNT_AMOUNT = Pattern.compile(
            "(?i)^ shall be (?:applied by substituting )?" + MONEY + "(?=[:.,; ]|$)");
    private static final Pattern DEFENSE_TITLE = Pattern.compile("(?i)^(?:making appropriations for the )?department of defense\\b");
    private static final Pattern FEDERAL_YEAR = Pattern.compile("(?i)fiscal year ending September 30,?\\s+(20\\d{2})");
    private static final Pattern FISCAL_YEAR = Pattern.compile("(?i)for fiscal year (20\\d{2})");
    private static final Pattern ADVANCE = Pattern.compile("(?i)^,\\s*(?:plus reimbursements,\\s*)?(?:which )?shall become available on October 1, (20\\d{2})");

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }

    private static Document parse(String source, Parser parser) {
        // Large XML files can spend most of the request budget inside jsoup before a row exists.
        return parser.parseInput(new StringReader(source) {
            @Override public int read(char[] buffer, int offset, int length) throws IOException {
                checkInterrupted();
                return super.read(buffer, offset, length);
            }
        }, "");
    }

    public static List<Line> extract(String source) {
        if (source == null || !source.contains("$") && !source.contains("expressed in thousands of dollars")) return List.of();
        List<Line> rows = new ArrayList<>();
        if (source.contains("<appropriations-") || source.contains("<division") && source.contains("<section")) {
            var doc = parse(source, Parser.xmlParser());
            int billYear = federalYear(doc);
            List<String> fundedSuballocations = new ArrayList<>();
            for (Element block : doc.select("appropriations-small, appropriations-intermediate, appropriations-major")) {
                checkInterrupted();
                Element text = block.selectFirst(":root > text");
                if (text == null || block.closest("quoted-block, toc") != null) continue;
                String clause = text.text();
                Matcher amount = GRANT.matcher(clause);
                if (!amount.find()) continue;
                List<String> path = new ArrayList<>();
                int rank = headingRank(block);
                for (Element node = block; node != null && rank > 0; node = node.previousElementSibling()) {
                    checkInterrupted();
                    int level = headingRank(node);
                    if (level == 0 || level > rank) continue;
                    Element header = node.selectFirst(":root > header");
                    String label = header == null ? "" : header.text();
                    if (!label.isBlank() && !label.startsWith("(")) path.add(0, label);
                    // A qualifier can occupy its own block immediately after an empty account heading.
                    Element previous = node.previousElementSibling();
                    boolean qualifiedHeading = (label.isBlank() || label.startsWith("(")) && previous != null
                            && headingRank(previous) == level && previous.selectFirst(":root > text") == null;
                    rank = qualifiedHeading ? level : level - 1;
                }
                if (path.isEmpty()) continue;
                Element title = block.closest("title");
                Element titleHeader = title == null ? null : title.selectFirst(":root > header");
                if (titleHeader != null) path.add(0, titleHeader.text());
                var division = block.closest("division");
                var heading = division == null ? doc.selectFirst("official-title") : division.selectFirst(":root > header");
                if (heading != null && DEFENSE_TITLE.matcher(heading.text()).find()) {
                    path.add(0, "Department of Defense");
                }
                int year = appropriationsYear(heading, billYear);
                Matcher advance = ADVANCE.matcher(clause.substring(amount.end()));
                if (advance.find()) year = Integer.parseInt(advance.group(1)) + 1;
                String label = String.join(" / ", path);
                if (fundedSuballocations.stream().anyMatch(label::startsWith)) continue;
                if (clause.substring(amount.end()).toLowerCase(Locale.ROOT).contains("from this appropriation as follows")) {
                    fundedSuballocations.add(label + " / ");
                }
                rows.add(new Line(label, dollars(amount.group(1)), year, amount.group()));
            }
            for (Element section : doc.select("division section")) {
                checkInterrupted();
                Element intro = section.selectFirst(":root > text");
                int fy = intro == null ? 0 : year(FISCAL_YEAR, intro.text());
                if (fy == 0) {
                    Element heading = section.closest("division").selectFirst(":root > header");
                    fy = appropriationsYear(heading, billYear);
                }
                if (intro == null || !ACCOUNT_LIST.matcher(intro.text()).find()) {
                    for (Element text : section.select(":root > text, :root > subsection > text")) {
                        var prefix = SINGLE_ACCOUNT.matcher(text.text());
                        var account = text.selectFirst(":root > quote");
                        if (account == null || !prefix.find() || !text.text().startsWith(account.text(), prefix.end())) continue;
                        var amount = SINGLE_ACCOUNT_AMOUNT.matcher(text.text().substring(prefix.end() + account.text().length()));
                        if (amount.find()) {
                            rows.add(new Line(account.text().replaceAll("\\s*—\\s*", " / "),
                                    dollars(amount.group(1)), fy, text.text()));
                        }
                    }
                    continue;
                }
                for (Element text : section.select(":root > paragraph > text, :root > subsection > text")) {
                    checkInterrupted();
                    Element account = text.selectFirst(":root > quote");
                    if (account == null) continue;
                    String clause = text.text();
                    String name = account.text();
                    Matcher amount = ACCOUNT_LEVEL.matcher(clause);
                    if (!amount.find() || !clause.substring(amount.end()).startsWith(name)) {
                        if (!clause.startsWith(name)) continue;
                        amount = QUOTED_ACCOUNT_AMOUNT.matcher(clause.substring(name.length()));
                        if (!amount.find()) continue;
                    }
                    String path = name.replaceAll("\\s*—\\s*", " / ");
                    if (path.equals(name)) {
                        Element title = section.closest("title");
                        Element header = title == null ? null : title.selectFirst(":root > header");
                        if (header != null) path = header.text() + " / " + name;
                    }
                    rows.add(new Line(path, dollars(amount.group(1)), fy, clause));
                }
            }
            if (!rows.isEmpty() || source.contains("<appropriations-")) return rows;
        }
        String text = source;
        if (source.stripLeading().startsWith("<")) {
            var doc = parse(source, Parser.htmlParser());
            var stateRows = StateBudgetTables.arizona(doc);
            if (!stateRows.isEmpty()) return stateRows;
            doc.select("del, s, strike").remove();
            text = doc.body().wholeText();
        }
        var stateRows = StateBudgetTables.extract(text);
        if (!stateRows.isEmpty()) return stateRows;
        int fy = year(STATE_YEAR, text);
        // Later sections of Long Bills amend prior appropriations and repeat department totals.
        var section = Pattern.compile("(?im)^\\s*SECTION 2\\.\\s*Appropriation\\.").matcher(text);
        if (fy > 0 && section.find()) {
            String operating = text.substring(section.end()).split("(?im)^\\s*SECTION [3-9]\\.", 2)[0];
            Matcher parts = PART.matcher(operating);
            while (parts.find()) {
                checkInterrupted();
                rows.add(new Line(clean(parts.group(1)), dollars(parts.group(2)), fy, clean(parts.group())));
            }
            if (!rows.isEmpty()) return rows;
        }
        Matcher plain = PLAIN_GRANT.matcher(text);
        while (plain.find()) {
            checkInterrupted();
            Matcher amount = GRANT.matcher(clean(plain.group(2)));
            if (amount.find()) rows.add(new Line(clean(plain.group(1)), dollars(amount.group(1)), 0, amount.group()));
        }
        Matcher state = STATE_CLAUSE.matcher(clean(text));
        while (state.find()) {
            checkInterrupted();
            rows.add(new Line(state.group(2), dollars(state.group(1)), 0, state.group()));
        }
        return rows;
    }

    static boolean verifiesNoFunding(String source) {
        if (source == null || source.isBlank() || BillTextPlaceholder.isPlaceholder(source)) return false;
        // An empty match is inconclusive for unfamiliar formats or legislation with monetary cues.
        String text = source.stripLeading().startsWith("<") ? parse(source, Parser.htmlParser()).text() : source;
        return ENACTING_TEXT.matcher(text).find() && !FUNDING_CUE.matcher(text).find();
    }

    static String citationText(String source) {
        return clean(parse(source, Parser.htmlParser()).text()).toLowerCase(Locale.ROOT);
    }

    static boolean citesPriorAppropriation(String citations, String priorSource, Set<Integer> priorYears) {
        if (!priorSource.contains("<division") && !priorSource.contains("<short-title")) return false;
        var doc = parse(priorSource, Parser.xmlParser());
        for (var heading : doc.select("division > header, section > text > quote > short-title")) {
            checkInterrupted();
            String title = clean(heading.text()).toLowerCase(Locale.ROOT);
            if (priorYears.contains(year(YEAR, title)) && citations.contains("the " + title)) return true;
        }
        return false;
    }

    static List<FundingRow> compareFunding(List<BillFunding.Line> lines, List<FundingRow> previous) {
        Map<BillFunding.Line, List<FundingRow>> matches = new HashMap<>();
        Map<FundingRow, Integer> uses = new HashMap<>();
        record AccountYear(String account, int year) {
            static AccountYear of(String path, int year) {
                return new AccountYear(path.substring(path.lastIndexOf('/') + 1).strip()
                        .toLowerCase(Locale.ROOT), year);
            }
        }
        Map<AccountYear, List<FundingRow>> priorAccounts = new HashMap<>();
        for (var row : previous) {
            checkInterrupted();
            if (row.year() > 0) priorAccounts.computeIfAbsent(AccountYear.of(row.label(), row.year()),
                    ignored -> new ArrayList<>()).add(row);
        }
        for (var line : lines) {
            checkInterrupted();
            var key = AccountYear.of(line.label(), line.year() - 1);
            var candidates = priorAccounts.getOrDefault(key, List.of()).stream()
                    .filter(row -> comparableAccounts(line.label(), row.label())).toList();
            matches.put(line, candidates);
            candidates.forEach(row -> uses.merge(row, 1, Integer::sum));
        }
        return lines.stream().map(line -> {
            var candidates = matches.get(line);
            var prior = candidates.size() == 1 && uses.get(candidates.getFirst()) == 1 ? candidates.getFirst() : null;
            return new FundingRow(line.label(), line.amount(), prior == null ? null : prior.current(),
                    prior == null ? null : prior.priorBill(), line.year(), line.evidence());
        }).sorted(java.util.Comparator.comparingLong(FundingRow::current).reversed()).toList();
    }

    // CR account lists can add a department prefix or omit an intermediate office.
    // Require both a common parent and an identical account, in a consistent hierarchy.
    private static boolean comparableAccounts(String left, String right) {
        if (left.equalsIgnoreCase(right)) return true;
        var a = List.of(left.toLowerCase(Locale.ROOT).split(" / "));
        var b = List.of(right.toLowerCase(Locale.ROOT).split(" / "));
        var shorter = a.size() < b.size() ? a : b;
        var longer = shorter == a ? b : a;
        if (shorter.size() < 2 || !shorter.getLast().equals(longer.getLast())) return false;
        if (a.size() > 1 && b.size() > 1 && a.get(a.size() - 2).equals(b.get(b.size() - 2))) return true;
        int i = 0;
        for (var part : longer) {
            checkInterrupted();
            if (part.equals(shorter.get(i)) && ++i == shorter.size()) return true;
        }
        return false;
    }

    private static int federalYear(Document doc) {
        Element officialTitle = doc.selectFirst("form > official-title");
        int year = appropriationsYear(officialTitle, 0);
        if (year != 0) return year;
        Element opening = doc.selectFirst("legis-body > section > text");
        return opening == null ? 0 : year(FEDERAL_YEAR, opening.text());
    }

    private static int appropriationsYear(Element heading, int fallback) {
        if (heading == null) return fallback;
        int year = year(YEAR, heading.text());
        if (year == 0) year = year(FEDERAL_YEAR, heading.text());
        return year == 0 ? fallback : year;
    }

    private static int headingRank(Element element) {
        return switch (element.tagName()) {
            case "appropriations-major" -> 1;
            case "appropriations-intermediate" -> 2;
            case "appropriations-small" -> 3;
            default -> 0;
        };
    }

    private static String clean(String text) { return text.replaceAll("\\s+", " ").strip(); }
    private static long dollars(String value) { return Long.parseLong(value.replace(",", "")); }
    private static int year(Pattern pattern, String text) {
        var match = pattern.matcher(clean(text));
        return match.find() ? Integer.parseInt(match.group(1)) : 0;
    }
}
