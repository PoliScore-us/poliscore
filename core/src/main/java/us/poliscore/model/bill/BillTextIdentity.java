package us.poliscore.model.bill;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

public class BillTextIdentity {
	private static final Pattern GPO_BILLS_FILE_NAME_PATTERN = Pattern.compile("(?i)^BILLS-\\d+[a-z]+\\d+([a-z]+(?:\\d+s?)?)$");
	private static final Pattern GPO_VERSION_TOKEN_PATTERN = Pattern.compile("(?i)^[a-z]+(?:\\d+s?)?$");

	public static Optional<String> congressVersionFromUrl(String url) {
		if (StringUtils.isBlank(url)) {
			return Optional.empty();
		}

		String fileName = url.substring(url.lastIndexOf('/') + 1);
		return congressVersionFromFileName(fileName);
	}

	public static Optional<String> congressVersionFromFileName(String fileName) {
		if (StringUtils.isBlank(fileName) || !StringUtils.startsWithIgnoreCase(fileName, "BILLS-")) {
			return Optional.empty();
		}

		String baseName = FilenameUtils.getBaseName(fileName);
		var matcher = GPO_BILLS_FILE_NAME_PATTERN.matcher(baseName);
		if (!matcher.matches()) {
			return Optional.empty();
		}

		return canonicalCongressVersionToken(matcher.group(1));
	}

	public static Optional<String> canonicalCongressVersionFromStoredVersion(String version, String billId) {
		if (StringUtils.isBlank(version)) {
			return Optional.empty();
		}

		String normalized = version.trim().toUpperCase();
		if (StringUtils.startsWithIgnoreCase(normalized, "BILLS-")) {
			return congressVersionFromFileName(normalized);
		}

		try {
			return Optional.of(BillTextPublishVersion.valueOf(normalized).name());
		} catch (IllegalArgumentException ignored) { }

		Optional<String> compactGpoVersion = canonicalCongressVersionToken(normalized);
		if (compactGpoVersion.isPresent()) {
			return compactGpoVersion;
		}

		return canonicalCongressVersionFromLegiscanVersion(normalized, billId);
	}

	public static boolean isCanonicalCongressVersion(String version) {
		return canonicalCongressVersionFromStoredVersion(version, null)
				.map(canonical -> StringUtils.equalsIgnoreCase(canonical, version))
				.orElse(false);
	}

	public static Optional<String> canonicalCongressVersionFromLegiscanVersion(String version, String billId) {
		String typeToken = legiscanVersionTypeToken(version).orElse(null);
		if (typeToken == null) {
			return Optional.empty();
		}

		return switch (typeToken) {
			case "INTRODUCED" -> Optional.of(isSenateBill(billId) ? "IS" : "IH");
			case "ENGROSSED" -> Optional.of(isSenateBill(billId) ? "ES" : "EH");
			case "ENROLLED" -> Optional.of("ENR");
			case "AMENDED",
					"ANALYSIS",
					"CHAPTERED",
					"COMMITTEE_SUBSTITUTE",
					"CONFERENCE_SUBSTITUTE",
					"DOC",
					"DRAFT",
					"FISCAL_NOTE",
					"PREFILED",
					"SUBSTITUTE",
					"VETO_MESSAGE",
					"VETO_RESPONSE" -> Optional.empty();
			default -> Optional.empty();
		};
	}

	private static Optional<String> legiscanVersionTypeToken(String version) {
		if (StringUtils.isBlank(version)) {
			return Optional.empty();
		}

		String suffix = StringUtils.substringAfterLast(version, "-");
		if (!StringUtils.isNumeric(suffix)) {
			return Optional.empty();
		}

		String typeToken = StringUtils.substringBeforeLast(version, "-").trim().toUpperCase();
		return StringUtils.isBlank(typeToken) ? Optional.empty() : Optional.of(typeToken);
	}

	private static Optional<String> canonicalCongressVersionToken(String versionToken) {
		if (StringUtils.isBlank(versionToken) || !GPO_VERSION_TOKEN_PATTERN.matcher(versionToken).matches()) {
			return Optional.empty();
		}

		String normalized = versionToken.trim().toUpperCase();
		String baseVersion = normalized
				.replaceFirst("\\d+S$", "")
				.replaceFirst("\\d+$", "");
		boolean knownBaseVersion = Arrays.stream(BillTextPublishVersion.values())
				.anyMatch(version -> version.name().equals(baseVersion));
		return knownBaseVersion ? Optional.of(normalized) : Optional.empty();
	}

	private static boolean isSenateBill(String billId) {
		String[] parts = billId == null ? new String[0] : billId.split("/");
		return parts.length > 4 && parts[4].toLowerCase().startsWith("s");
	}

	private BillTextIdentity() { }
}
