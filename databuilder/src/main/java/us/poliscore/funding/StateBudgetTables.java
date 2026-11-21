package us.poliscore.funding;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import us.poliscore.funding.BillFunding.Line;

/** Reads only the explicitly labeled totals in the main budget tables. */
final class StateBudgetTables {
    private static final String AMOUNT = "([0-9][0-9,]*)";
    private static final Pattern NM_BUDGET = Pattern.compile("(?im)^.*?Section 4\\.\\h+FISCAL YEAR (20\\d{2}) APPROPRIATIONS");
    private static final Pattern NM_TOTAL = Pattern.compile(
            "(?m)^\\h*\\d+\\h+TOTAL ([A-Z][A-Z &,/'-]+?)\\h+(?:[\\d,]+\\.\\d\\h+)*([\\d,]+\\.\\d)\\h*$");
    private static final Pattern NM_PROSE_PART = Pattern.compile("(?m)^\\h*\\d+\\h+[A-Z]\\.\\h+([A-Z][A-Z &,/'-]+)\\.--");
    private static final Pattern NM_APPROPRIATED = Pattern.compile("(?i)\\b(?:is|are)\\s+appropriated\\b");
    private static final Pattern NM_PROSE_AMOUNT = Pattern.compile("\\(\\$" + AMOUNT + "\\)");
    private static final Pattern GA_YEAR = Pattern.compile("(?m)^\\h*HB\\h+\\d+\\h+FY(20\\d{2})A?\\h*$");
    private static final Pattern GA_SECTION = Pattern.compile("(?m)^\\h*Section \\d+:\\h+([^\\r\\n]+?)\\h*$");
    private static final Pattern GA_PROGRAM = Pattern.compile("(?m)^\\h*\\d+\\.\\d+\\.\\h+");
    private static final Pattern GA_TOTAL = Pattern.compile("(?m)^\\h*\\d+\\h+State Funds\\h+\\$\\h*" + AMOUNT + "\\h*$");
    private static final String TX_AMOUNTS = "\\h+\\$?\\h*" + AMOUNT + "\\h+\\$?\\h*" + AMOUNT + "\\h*$";
    private static final Pattern TX_RECAP_START = Pattern.compile("(?im)^\\h*ARTICLE I\\h*[-–]\\h*General Government" + TX_AMOUNTS);
    private static final Pattern TX_RECAP_END = Pattern.compile("(?im)^\\h*GRAND TOTAL, All Funds" + TX_AMOUNTS);
    private static final Pattern TX_ARTICLE = Pattern.compile(
            "(?im)^\\h*ARTICLE [IVX]+\\h*[-–]\\h*(.+?)" + TX_AMOUNTS);
    private static final Pattern TX_YEARS = Pattern.compile("(?m)^\\h*(20\\d{2})\\h+(20\\d{2})\\h*$");
    private static final Pattern AZ_BUDGET = Pattern.compile(
            "(?i)(?:general appropriations act\\.?;\\h*(20\\d{2})-20\\d{2}|(20\\d{2})-20\\d{2};\\h*general appropriations act)");
    private static final Pattern AZ_HEADING = Pattern.compile("(?i)^Sec(?:tion)?\\.?(?:\\h+)\\d+\\.\\h+([A-Z].*)$");
    private static final Pattern AZ_YEAR = Pattern.compile("^(20\\d{2})-(\\d{2})$");
    private static final Pattern AZ_TOTAL = Pattern.compile("(?i)^Total appropriation\\b.*?\\$\\h*" + AMOUNT);

    private StateBudgetTables() {}

    static List<Line> extract(String text) {
        var nm = NM_BUDGET.matcher(text);
        if (text.contains("expressed in thousands of dollars") && nm.find()) {
            var rows = new ArrayList<Line>();
            String tables = text.substring(nm.end()).split("(?im)^.*?Section 5\\.\\h+", 2)[0];
            var total = NM_TOTAL.matcher(tables);
            int year = Integer.parseInt(nm.group(1));
            while (total.find()) {
                long dollars = new BigDecimal(total.group(2).replace(",", "")).movePointRight(3).longValueExact();
                rows.add(new Line(total.group(1), dollars, year, total.group().strip()));
            }
            return rows.isEmpty() ? newMexicoProse(tables, year) : rows;
        }

        var ga = GA_YEAR.matcher(text);
        if (ga.find() && text.contains("State Fiscal Year beginning July 1")) {
            var rows = new ArrayList<Line>();
            var heading = GA_SECTION.matcher(text);
            int year = Integer.parseInt(ga.group(1));
            int start = -1;
            String label = null;
            while (heading.find()) {
                if (start >= 0) addGeorgiaTotal(rows, text, start, heading.start(), label, year);
                start = heading.end();
                label = heading.group(1);
            }
            if (start >= 0) addGeorgiaTotal(rows, text, start, text.length(), label, year);
            return rows;
        }

        return texasRecap(text);
    }

    private static List<Line> newMexicoProse(String section, int year) {
        var parts = NM_PROSE_PART.matcher(section);
        var rows = new ArrayList<Line>();
        if (!parts.find()) return rows;
        boolean next;
        do {
            String label = parts.group(1);
            int start = parts.end();
            next = parts.find();
            String clause = section.substring(start, next ? parts.start() : section.length())
                    .replaceAll("(?m)^\\h*\\d+\\h+", "")
                    .replaceAll("(?m)^\\h*(?:\\.\\d+\\.\\w+|-\\h+\\d+\\h+-|underscored material = new|\\[bracketed material\\] = delete)\\h*$", "")
                    .replaceAll("\\s+", " ").strip();
            var appropriation = NM_APPROPRIATED.matcher(clause);
            if (!appropriation.find()) continue;
            var amounts = NM_PROSE_AMOUNT.matcher(clause).region(0, appropriation.start());
            if (!amounts.find()) continue;
            long sum = 0;
            do {
                sum = Math.addExact(sum, dollars(amounts.group(1)));
            } while (amounts.find());
            rows.add(new Line(label, sum, year, (label + ". " + clause).strip()));
        } while (next);
        return rows;
    }

    private static List<Line> texasRecap(String text) {
        var grandTotal = TX_RECAP_END.matcher(text);
        if (!grandTotal.find()) return List.of();
        var start = TX_RECAP_START.matcher(text).region(0, grandTotal.start());
        int startIndex = -1;
        while (start.find()) startIndex = start.start();
        if (startIndex < 0) return List.of();

        var years = TX_YEARS.matcher(text).region(0, startIndex);
        int first = 0;
        int second = 0;
        while (years.find()) {
            first = Integer.parseInt(years.group(1));
            second = Integer.parseInt(years.group(2));
        }
        if (first == 0 || second != first + 1) return List.of();

        var rows = new ArrayList<Line>();
        var article = TX_ARTICLE.matcher(text).region(startIndex, grandTotal.start());
        long firstSum = 0;
        long secondSum = 0;
        while (article.find()) {
            long firstAmount = dollars(article.group(2));
            long secondAmount = dollars(article.group(3));
            firstSum = Math.addExact(firstSum, firstAmount);
            secondSum = Math.addExact(secondSum, secondAmount);
            rows.add(new Line(article.group(1), firstAmount, first, article.group().strip()));
            rows.add(new Line(article.group(1), secondAmount, second, article.group().strip()));
        }
        return firstSum == dollars(grandTotal.group(1)) && secondSum == dollars(grandTotal.group(2)) ? rows : List.of();
    }

    private static void addGeorgiaTotal(List<Line> rows, String text, int start, int end, String label, int year) {
        // Section totals precede the individual programs and use the State Funds basis.
        var program = GA_PROGRAM.matcher(text).region(start, end);
        int sectionSummaryEnd = program.find() ? program.start() : end;
        var total = GA_TOTAL.matcher(text).region(start, sectionSummaryEnd);
        if (total.find() && total.start() - start < 3500) {
            rows.add(new Line(label, dollars(total.group(1)), year, total.group().strip()));
        }
    }

    static List<Line> arizona(Document doc) {
        var budget = AZ_BUDGET.matcher(doc.text());
        if (!budget.find()) return List.of();
        int budgetYear = Integer.parseInt(budget.group(1) != null ? budget.group(1) : budget.group(2));
        doc.select("span.O, strike, del, s").remove(); // Arizona marks prior language with the O class.
        var paragraphs = doc.select("p");
        var rows = new ArrayList<Line>();
        String label = null;
        for (int i = 0; i < paragraphs.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var p = paragraphs.get(i);
            String text = p.text();
            var heading = AZ_HEADING.matcher(text);
            if (heading.matches()) {
                label = null;
                if (i + 1 < paragraphs.size()) {
                    var period = AZ_YEAR.matcher(paragraphs.get(i + 1).text());
                    if (period.matches() && Integer.parseInt(period.group(1)) == budgetYear) {
                        label = heading.group(1);
                    }
                }
            }
            if (label == null || !text.toLowerCase(java.util.Locale.ROOT).startsWith("total appropriation")) continue;
            if (!text.contains("$") && i + 1 < paragraphs.size()) text += " " + paragraphs.get(i + 1).text();
            var total = AZ_TOTAL.matcher(text);
            if (total.find()) {
                rows.add(new Line(label, dollars(total.group(1)), budgetYear + 1, text));
                label = null;
            }
        }
        return rows;
    }

    private static long dollars(String value) {
        return Long.parseLong(value.replace(",", ""));
    }
}
