package us.poliscore.parsing;

import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

/** Identifies short format-unavailable notices published in place of legislation. */
public final class BillTextPlaceholder {
    private static final Pattern UNAVAILABLE_FORMAT_NOTICE = Pattern.compile("\\b(html|word|rtf|text|pdf)\\b.{0,100}\\b(version|versions|copy|copies|document|documents)\\b.{0,100}\\b(not\\s+available|unavailable|not\\s+provided|cannot\\s+be\\s+displayed)\\b|\\b(not\\s+available|unavailable|not\\s+provided|cannot\\s+be\\s+displayed)\\b.{0,100}\\b(html|word|rtf|text|pdf)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMAT_REDIRECT_NOTICE = Pattern.compile("\\b(see|view|open|refer\\s+to|consult|download)\\b.{0,100}\\b(pdf|word|html|website|site|link|document)\\b|\\b(pdf|word|html)\\b.{0,100}\\b(content\\s+of\\s+(this\\s+)?bill|bill\\s+content|full\\s+text)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGISLATIVE_TEXT_MARKER = Pattern.compile("\\b(be\\s+it\\s+enacted|resolved,|section\\s+\\d+|sec\\.\\s+\\d+|article\\s+[ivxlcdm0-9]+|chapter\\s+\\d+|subchapter\\s+[a-z0-9]+|amend(ed|ing)|appropriat(e|ion)|whereas)\\b", Pattern.CASE_INSENSITIVE);

    private BillTextPlaceholder() {}

    public static boolean isPlaceholder(String text) {
        String normalized = normalizeBillTextPlaceholderNotice(text);
        if (normalized.isBlank() || normalized.length() > 1_500 || LEGISLATIVE_TEXT_MARKER.matcher(normalized).find()) {
            return false;
        }

        return UNAVAILABLE_FORMAT_NOTICE.matcher(normalized).find()
                && FORMAT_REDIRECT_NOTICE.matcher(normalized).find();
    }

    private static String normalizeBillTextPlaceholderNotice(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }

        String value = text;
        if (looksLikeHtml(value)) {
            value = Jsoup.parse(value).text();
        }

        return value
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeHtml(String text) {
        return Pattern.compile("(?is)<\\s*(html|head|body|p|div|span|table|tr|td|br)\\b").matcher(text).find();
    }
}
