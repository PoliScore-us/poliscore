package us.poliscore.bill;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;

/**
 * Detects bill-text versions whose legislative language is unchanged even though
 * their publication wrappers or version identifiers differ.
 */
public final class BillTextMaterialityDetector {
	private static final List<Pattern> PUBLICATION_LINE_PATTERNS = List.of(
			Pattern.compile("(?i)^\\s*(?:page\\s+)?\\d+\\s*$"),
			Pattern.compile("(?i)^\\s*(?:introduced|engrossed|enrolled|reported|referred|re-engrossed|printed)\\s+(?:in|to|by|from|for|as|on|the|house|senate|committee|governor|president|chamber|calendar|\\d|[,./-])+\\s*$"),
			Pattern.compile("(?i)^\\s*(?:bill|document)\\s+(?:status|version)\\s*:\\s*.+$"),
			Pattern.compile("(?i)^\\s*(?:last updated|printed on|generated on)\\s*:\\s*.+$"));
	public static boolean hasSameSubstantiveText(BillText previous, BillText latest) {
		if (previous == null || latest == null) return false;

		String previousText = substantiveText(previous);
		String latestText = substantiveText(latest);
		return StringUtils.isNotBlank(previousText)
				&& StringUtils.isNotBlank(latestText)
				&& previousText.equals(latestText);
	}

	static String substantiveText(BillText billText) {
		String source = billText == null ? null : billText.getDocument();
		if (StringUtils.isBlank(source)) return "";

		BillTextFormat format = billText.getEffectiveFormat();
		String rendered = switch (format) {
			case XML, CONGRESS_BILL_XML -> renderXmlLegislativeBody(source);
			case HTML -> renderHtml(source);
			// RTF text extraction is not reliably lossless here. Requiring its normalized
			// source to match exactly favors a redundant AI request over a false alias.
			case RTF -> source;
			default -> source;
		};
		return normalize(stripPublicationLines(rendered));
	}

	private static String renderXmlLegislativeBody(String source) {
		Document document = Jsoup.parse(source, "", Parser.xmlParser());
		Element legislativeBody = document.selectFirst("legis-body");
		// Congress XML puts publication metadata, status/form labels, and
		// attestations outside legis-body. Comparing only this node detects the
		// common case where the wrapper advances but the legislation does not.
		if (legislativeBody == null) return document.text();
		Element substantiveBody = legislativeBody.clone();
		// A generated table of contents is navigational publication furniture;
		// the actual sections remain in the body and are compared below.
		substantiveBody.select("toc").remove();
		return substantiveBody.text();
	}

	private static String renderHtml(String source) {
		Document document = Jsoup.parse(source);
		document.select("script, style, noscript").remove();
		return document.body() == null ? document.text() : document.body().text();
	}

	private static String normalize(String text) {
		return Normalizer.normalize(StringUtils.defaultString(text), Normalizer.Form.NFC)
				.replace('\u00A0', ' ')
				.replaceAll("\\s+", " ")
				.trim();
	}

	private static String stripPublicationLines(String text) {
		if (StringUtils.isBlank(text)) return "";
		return text.lines()
				.filter(line -> PUBLICATION_LINE_PATTERNS.stream().noneMatch(pattern -> pattern.matcher(line).matches()))
				.collect(Collectors.joining("\n"));
	}

	private BillTextMaterialityDetector() { }
}
