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

/** Extract explicit appropriations only. Authorization ceilings and unpriced CR formulas have no row. */
public final class BillFunding {
    public record Line(String label, long amount, int year, String evidence) {}
    private static final String MONEY = "\\$([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?![0-9,.]\\d)";
    private static final Pattern GRANT = Pattern.compile("(?i)^For (?:necessary expenses|expenses necessary|payments|the acquisition|salaries and expenses)\\b[^$:]{0,3500}?[,;]\\s*" + MONEY + "(?!\\s+(?:thousand|million|billion|trillion)\\b)(?=[.,;:\\s]|$)");
    private static final Pattern YEAR = Pattern.compile("(?i)appropriations act,?\\s+(20\\d{2})");
    private static final Pattern STATE_YEAR = Pattern.compile("(?i)fiscal year beginning July 1,?\\s+(20\\d{2})");
    private static final Pattern PART = Pattern.compile("(?im)^\\s*TOTALS PART [IVXLCDM]+\\s*\\(([A-Z\\s&'/-]{1,150})\\)\\d*\\s*" + MONEY);
    private static final Pattern STATE_CLAUSE = Pattern.compile("(?i)(?<![\\w$-])" + MONEY + " is appropriated to (?:the )?([a-z][a-z ,&'-]{2,120}?) for (?:use by |the |implementation|allocation|distribution)");
    private static final Pattern PLAIN_GRANT = Pattern.compile("(?im)^[ \\t]*([A-Za-z][A-Za-z &,'–—-]{2,90})[ \\t]*\\R(?:\\s*\\([^()]{1,80}\\)\\s*\\R)?\\s*(For (?:necessary expenses|expenses necessary|payments|salaries and expenses)\\b[^$:]{0,3500}?[,;]\\s*\\$[\\d,]+)(?!\\s+(?:thousand|million|billion|trillion)\\b)(?=[.,;:\\s]|$)");

    private static final Pattern ACCOUNT_LEVEL = Pattern.compile("^" + MONEY + " for ", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT_LIST = Pattern.compile("(?i)levels? for (?:each of the following|appropriations) accounts\\b");
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
        if (source == null || !source.contains("$")) return List.of();
        List<Line> rows = new ArrayList<>();
        if (source.contains("<appropriations-") || source.contains("<division") && source.contains("<section")) {
            var doc = parse(source, Parser.xmlParser());
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
                    boolean qualifiedHeading = label.startsWith("(") && previous != null
                            && headingRank(previous) == level && previous.selectFirst(":root > text") == null;
                    rank = qualifiedHeading ? level : level - 1;
                }
                if (path.isEmpty()) continue;
                Element title = block.closest("title");
                Element titleHeader = title == null ? null : title.selectFirst(":root > header");
                if (titleHeader != null) path.add(0, titleHeader.text());
                var division = block.closest("division");
                var heading = division == null ? doc.selectFirst("official-title") : division.selectFirst(":root > header");
                int year = year(YEAR, heading == null ? "" : heading.text());
                Matcher advance = ADVANCE.matcher(clause.substring(amount.end()));
                if (advance.find()) year = Integer.parseInt(advance.group(1)) + 1;
                rows.add(new Line(String.join(" / ", path), dollars(amount.group(1)), year, amount.group()));
            }
            for (Element section : doc.select("division section")) {
                checkInterrupted();
                Element intro = section.selectFirst(":root > text");
                if (intro == null || !ACCOUNT_LIST.matcher(intro.text()).find()) continue;
                int fy = year(FISCAL_YEAR, intro.text());
                if (fy == 0) {
                    Element heading = section.closest("division").selectFirst(":root > header");
                    fy = year(YEAR, heading == null ? "" : heading.text());
                }
                for (Element text : section.select(":root > paragraph > text, :root > subsection > text")) {
                    checkInterrupted();
                    Element account = text.selectFirst(":root > quote");
                    Matcher amount = ACCOUNT_LEVEL.matcher(text.text());
                    if (account == null || !amount.find() || !text.text().substring(amount.end()).startsWith(account.text())) continue;
                    rows.add(new Line(account.text().replaceAll("\\s*—\\s*", " / "), dollars(amount.group(1)), fy, text.text()));
                }
            }
            if (!rows.isEmpty() || source.contains("<appropriations-")) return rows;
        }
        String text = source;
        if (source.stripLeading().startsWith("<")) {
            var doc = parse(source, Parser.htmlParser());
            doc.select("del, s, strike").remove();
            text = doc.body().wholeText();
        }
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
        int i = 0;
        for (var part : longer) {
            checkInterrupted();
            if (part.equals(shorter.get(i)) && ++i == shorter.size()) return true;
        }
        return false;
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
