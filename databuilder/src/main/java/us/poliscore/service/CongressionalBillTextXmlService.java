package us.poliscore.service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.SneakyThrows;
import us.poliscore.dataset.LegiscanDatasetProvider;
import us.poliscore.entrypoint.GPOBulkBillTextFetcher;
import us.poliscore.legiscan.view.LegiscanTextMetadataView;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;

@ApplicationScoped
public class CongressionalBillTextXmlService {

	private static final Pattern BILL_DTD_PATTERN = Pattern.compile("<!DOCTYPE\\s+bill\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern BILL_ROOT_PATTERN = Pattern.compile("<bill(?:\\s|>)", Pattern.CASE_INSENSITIVE);

	/**
	 * LegiScan remains our source of truth for text metadata and version identity, but
	 * its Congressional text payloads are commonly PDF-derived. For U.S. Congress,
	 * prefer the govinfo/Congress XML Bill DTD payload so the web app can render the
	 * official bill structure instead of PDF-extracted text. The returned BillText
	 * deliberately keeps the LegiScan doc id and LegiScan-derived version code so the
	 * S3 object key convention is preserved exactly.
	 */
	public Optional<BillText> fetchXmlBillText(Bill bill, LegiscanTextMetadataView metadata) {
		if (bill == null || metadata == null || !LegislativeNamespace.US_CONGRESS.equals(bill.getNamespace())) {
			return Optional.empty();
		}

		for (String url : candidateXmlUrls(metadata)) {
			try {
				String xml = fetchXml(url);
				BillTextFormat format = resolveXmlFormat(xml);
				if (format != null) {
					LocalDate date = firstNonNull(metadata.getDate(), GPOBulkBillTextFetcher.parseDate(xml), bill.getIntroducedDate());
					if (date == null) {
						Log.warn("GPO XML bill text had no resolvable date for " + bill.getId() + " from " + url);
						return Optional.empty();
					}

					return Optional.of(BillText.factory(
							bill.getId(),
							metadata.getDocId(),
							xml,
							date,
							LegiscanDatasetProvider.buildBillTextVersion(metadata),
							format));
				}
			}
			catch (Exception ex) {
				Log.debug("Unable to fetch Congressional XML bill text from " + url + " for " + bill.getId() + ": " + ex.getMessage());
			}
		}

		return Optional.empty();
	}

	protected Set<String> candidateXmlUrls(LegiscanTextMetadataView metadata) {
		Set<String> urls = new LinkedHashSet<>();
		String stateLink = metadata.getStateLink();
		if (stateLink == null || stateLink.isBlank()) {
			return urls;
		}

		String cleanLink = stateLink.trim();
		if (cleanLink.toLowerCase().endsWith(".pdf")) {
			urls.add(cleanLink.substring(0, cleanLink.length() - 4) + ".xml");
		}
		else if (cleanLink.toLowerCase().endsWith(".htm") || cleanLink.toLowerCase().endsWith(".html")) {
			urls.add(cleanLink.replaceFirst("(?i)\\.html?$", ".xml"));
		}

		String fileName = cleanLink.substring(cleanLink.lastIndexOf('/') + 1);
		String packageName = fileName.replaceFirst("(?i)\\.(pdf|xml|html?|txt)$", "");
		if (!packageName.isBlank() && packageName.startsWith("BILLS-")) {
			urls.add("https://www.govinfo.gov/content/pkg/" + packageName + "/xml/" + packageName + ".xml");
		}

		return urls;
	}

	@SneakyThrows
	private String fetchXml(String url) {
		RetryPolicy<Object> retryPolicy = RetryPolicy.<Object>builder()
				.handle(SocketTimeoutException.class, IOException.class)
				.withBackoff(2, 15, ChronoUnit.SECONDS)
				.withJitter(0.25)
				.withMaxRetries(3)
				.build();

		return Failsafe.with(retryPolicy).get(() -> {
			try (var in = new URL(url).openStream()) {
				return IOUtils.toString(in, StandardCharsets.UTF_8);
			}
		});
	}

	private BillTextFormat resolveXmlFormat(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}

		String trimmed = text.stripLeading();
		if (!(trimmed.startsWith("<?xml") || trimmed.startsWith("<"))) {
			throw new IllegalStateException("Fetched Congressional bill text was not XML.");
		}

		if (isCongressBillDtdXml(trimmed)) {
			return BillTextFormat.CONGRESS_BILL_XML;
		}

		return BillTextFormat.XML;
	}

	private boolean isCongressBillDtdXml(String xml) {
		return BILL_DTD_PATTERN.matcher(xml).find()
				&& BILL_ROOT_PATTERN.matcher(xml).find()
				&& xml.contains("<legis-body");
	}

	@SafeVarargs
	private final <T> T firstNonNull(T... values) {
		for (T value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}
}
