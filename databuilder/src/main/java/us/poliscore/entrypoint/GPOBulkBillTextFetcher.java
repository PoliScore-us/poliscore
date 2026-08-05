package us.poliscore.entrypoint;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import us.poliscore.PoliscoreUtil;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextIdentity;
import us.poliscore.model.bill.BillTextPublishVersion;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.storage.LocalCachedS3Service;

/**
 * Used to fetch bulk bill data from the GPO's bulk bill store. More info at:
 * 
 * https://www.govinfo.gov/bulkdata/BILLS
 * 
 * Accomplishes in a few minutes what takes the USC bill text fetcher weeks to accomplish, however does not support congress before 113
 */
@QuarkusMain(name="GPOBulkBillTextFetcher")
public class GPOBulkBillTextFetcher implements QuarkusApplication {
	
	public static final String URL_TEMPLATE = "https://www.govinfo.gov/bulkdata/BILLS/{{congress}}/{{session}}/{{type}}/BILLS-{{congress}}-{{session}}-{{type}}.zip";
	private static final Pattern DUBLIN_CORE_DATE_PATTERN = Pattern.compile("<(?:\\w+:)?date>([^<]+)</(?:\\w+:)?date>");
	private static final Pattern ACTION_DATE_PATTERN = Pattern.compile("<action-date[^>]*date=\"(\\d{8})\"");
	private static final Pattern ATTESTATION_DATE_PATTERN = Pattern.compile("<attestation-date[^>]*date=\"(\\d{8})\"");
	private static final Pattern MODS_DATE_ISSUED_PATTERN = Pattern.compile("<(?:\\w+:)?dateIssued[^>]*>([^<]+)</(?:\\w+:)?dateIssued>");
	
	public static List<String> FETCH_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	@Inject private LocalCachedS3Service s3;
	@Inject private GovernmentDataService data;
	
	@SneakyThrows
	public void process(PoliscoreDatasetIF dataset)
	{
		val store = PoliscoreUtil.cacheDir("bill-text");
//		FileUtils.deleteQuietly(store);
		store.mkdirs();
		
		data.importAllDatasets();
		
		dataset.optimizeExists(s3, BillText.class);
		dataset.optimizeExists(s3, BillInterpretation.class);
		
		val congressStore = new File(store, dataset.getCode());
		congressStore.mkdir();
		
		for (String billType : FETCH_BILL_TYPE)
		{
			val typeStore = new File(congressStore, String.valueOf(billType));
			typeStore.mkdir();
			
			// Download and unzip
			for (int session : new int[] { 1, 2 })
			{
				val url = URL_TEMPLATE.replaceAll("\\{\\{congress\\}\\}", dataset.getCode())
							.replaceAll("\\{\\{session\\}\\}", String.valueOf(session))
							.replaceAll("\\{\\{type\\}\\}", String.valueOf(billType));
				
				val zip = new File(typeStore, dataset.getCode() + "-" + billType + ".zip");
				
				// TODO : timestamp code found not working
				if (zip.exists()) { // && new Date().getTime() - zip.lastModified() > 24 * 60 * 60 * 1000
					zip.delete();
				} else if (zip.exists()) { continue; }
				
				RetryPolicy<Object> retryPolicy = RetryPolicy.<Object>builder()
					    .handle(SocketTimeoutException.class, IOException.class,
					    		ZipException.class // Congress occasionally gives us a bad zip. Just try it again.
					    		)
					    .withBackoff(5, 30, ChronoUnit.SECONDS)
					    .withJitter(0.25)
					    .withMaxRetries(5)
					    .onRetry(e -> Log.warn("Retrying due to " + e.getLastException().getMessage()))
					    .onFailure(e -> Log.error("Retries exhausted", e.getException()))
					    .build();
				
				Failsafe.with(retryPolicy).get(() -> {
					try
					{
						Log.info("Downloading " + url + " to " + zip.getAbsolutePath());
						IOUtils.copy(new URL(url).openStream(), new FileOutputStream(zip));
						
						Log.info("Extracting " + zip.getAbsolutePath() + " to " + typeStore.getAbsolutePath());
						new ZipFile(zip).extractAll(typeStore.getAbsolutePath());
					}
					catch(FileNotFoundException ex)
					{
						if (session != 2) // Session 2 may not exist yet
							throw ex;
					}
					return new Object();
				});
			}
			
			// Upload to S3
			Map<String, List<File>> filesByBill = PoliscoreUtil.allFilesWhere(typeStore, f -> f.getName().endsWith(".xml")).stream()
					.sorted(Comparator.comparing(File::getName))
					.collect(Collectors.groupingBy(
							f -> buildBillId(dataset, billType, f),
							java.util.LinkedHashMap::new,
							Collectors.toList()));
			
			for (val entry : filesByBill.entrySet()) {
				try {
					syncBillTextVersions(entry.getKey(), entry.getValue());
				}
				catch (Throwable t) {
					Log.error("Exception encountered processing " + entry.getKey(), t);
				}
			}
		}
		
		dataset.clearExistsOptimize(s3, BillText.class);
		
		Log.info("Downloaded all bill text!");
	}
	
	@SneakyThrows
	public static LocalDate parseDate(String text)
	{
		val dublinCoreMatch = DUBLIN_CORE_DATE_PATTERN.matcher(text);
		if (dublinCoreMatch.find()) {
			return LocalDate.parse(dublinCoreMatch.group(1).trim(), DateTimeFormatter.ISO_LOCAL_DATE);
		}
		
		val actionDateMatch = ACTION_DATE_PATTERN.matcher(text);
		if (actionDateMatch.find()) {
			return LocalDate.parse(actionDateMatch.group(1), DateTimeFormatter.BASIC_ISO_DATE);
		}
		
		val attestationDateMatch = ATTESTATION_DATE_PATTERN.matcher(text);
		if (attestationDateMatch.find()) {
			return LocalDate.parse(attestationDateMatch.group(1), DateTimeFormatter.BASIC_ISO_DATE);
		}
		
		return null;
	}
	
	@SneakyThrows
	public Optional<String> getBillText(Bill bill)
	{
		val parent = PoliscoreUtil.cacheDir("bill-text/" + bill.getSessionCode() + "/" + bill.getType());
		File[] childFiles = parent.listFiles();
		if (childFiles == null) {
			return Optional.empty();
		}
		
		val text = Arrays.asList(childFiles).stream()
				.filter(f -> f.getName().contains(bill.getSessionCode() + bill.getType().toLowerCase() + bill.getNumber()))
				.max((a,b) -> BillTextPublishVersion.parseFromBillTextName(a.getName()).billMaturityCompareTo(BillTextPublishVersion.parseFromBillTextName(b.getName())));
		
		if (text.isPresent())
		{
			return Optional.of(FileUtils.readFileToString(text.get(), "UTF-8"));
		}
		else
		{
			return Optional.empty();
		}
	}
	
	public static void main(String[] args) {
		Quarkus.run(GPOBulkBillTextFetcher.class, args);
	}
	
	@Override
    public int run(String... args) throws Exception {
		for (val dataset : data.getBuildDatasets())
			process(dataset);
        
        Quarkus.waitForExit();
        return 0;
    }
	
	@SneakyThrows
	protected void syncBillTextVersions(String billId, List<File> versionFiles) {
		val billTexts = versionFiles.stream()
				.map(file -> buildBillText(billId, file))
				.sorted(Comparator.comparing(BillText::getVersion)
						.thenComparing(BillText::getLastUpdated, Comparator.nullsFirst(Comparator.naturalOrder())))
				.collect(Collectors.toList());
		
		for (val billText : billTexts) {
			upsertVersionedBillText(billText);
		}
		
//		migrateProviderSpecificBillTextAliases(billId, billTexts);
//		migrateLegacyBillText(billId, billTexts);
	}
	
	protected String buildBillId(PoliscoreDatasetIF dataset, String billType, File f) {
		return Bill.generateId(dataset.getNamespace(), dataset.getCode(), CongressionalBillType.valueOf(billType.toUpperCase()), extractBillNumber(dataset.getCode(), billType, f.getName()));
	}
	
	protected int extractBillNumber(String datasetCode, String billType, String fileName) {
		String prefix = "BILLS-" + datasetCode + billType;
		if (!fileName.startsWith(prefix)) {
			throw new IllegalArgumentException("Unexpected GPO file name " + fileName + " for dataset " + datasetCode + " and bill type " + billType);
		}
		
		String remainder = fileName.substring(prefix.length());
		String number = remainder.replaceFirst("^([0-9]+).*$", "$1");
		if (!number.matches("\\d+")) {
			throw new IllegalArgumentException("Unable to extract bill number from GPO file name " + fileName);
		}
		
		return Integer.parseInt(number);
	}
	
	@SneakyThrows
	protected BillText buildBillText(String billId, File file) {
		String version = BillTextIdentity.congressVersionFromFileName(file.getName())
				.orElseGet(() -> BillTextPublishVersion.parseFromBillTextName(file.getName()).name());
		String text = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
		LocalDate date = parseDate(text);
		if (date == null) {
			date = fetchGovInfoModsDate(file);
		}
		if (date == null) {
			throw new IllegalStateException("Unable to resolve bill text date for " + billId + " from " + file.getName());
		}
		return BillText.factory(billId, null, text, date, version, us.poliscore.model.bill.BillTextFormat.CONGRESS_BILL_XML);
	}
	
	protected void upsertVersionedBillText(BillText candidate) {
		val existing = s3.get(candidate.getId(), BillText.class);
		if (existing.isPresent()) {
			if (existing.get().getLastUpdate() == null && candidate.getLastUpdate() != null) {
				existing.get().setLastUpdate(candidate.getLastUpdate());
				s3.put(existing.get());
			}
			return;
		}
		
		s3.put(candidate);
	}

	protected LocalDate fetchGovInfoModsDate(File file) {
		String packageId = FilenameUtils.getBaseName(file.getName());
		String url = "https://www.govinfo.gov/metadata/pkg/" + packageId + "/mods.xml";

		try {
			String mods = IOUtils.toString(new URL(url).openStream(), StandardCharsets.UTF_8);
			val match = MODS_DATE_ISSUED_PATTERN.matcher(mods);
			if (match.find()) {
				return LocalDate.parse(match.group(1).trim(), DateTimeFormatter.ISO_LOCAL_DATE);
			}
		} catch (Exception e) {
			Log.warn("Unable to resolve govinfo MODS date for " + file.getName() + " from " + url + ": " + e.getMessage());
		}

		return null;
	}
	
	protected void migrateLegacyBillText(String billId, List<BillText> versionedBillTexts) {
		val legacyId = BillText.generateId(billId);
		
		if (!s3.exists(legacyId, BillText.class)) return;
		
		// The versioned GPO upload already happened above, so we only need to retire the legacy key.
		s3.delete(legacyId, BillText.class);
		Log.info("Migrated legacy bill text " + legacyId + " to versioned key.");
	}

	protected void migrateProviderSpecificBillTextAliases(String billId, List<BillText> canonicalBillTexts) {
		String sessionKey = getSessionKey(billId);
		String objectKey = getVersionedObjectKeyPrefix(billId);
		Set<String> canonicalVersions = canonicalBillTexts.stream()
				.map(BillText::getVersion)
				.collect(Collectors.toCollection(HashSet::new));

		for (var existing : s3.query(BillText.class, sessionKey, objectKey)) {
			if (!billId.equals(existing.getBillId()) || StringUtils.isBlank(existing.getVersion()) || canonicalVersions.contains(existing.getVersion())) {
			    continue;
			}

			val canonicalTarget = selectCanonicalMigrationTarget(existing, canonicalBillTexts);
			if (canonicalTarget.isEmpty() || existing.getId().equals(canonicalTarget.get().getId())) {
				continue;
			}

			migrateBillInterpretationVersionAliases(billId, existing.getVersion(), canonicalTarget.get().getVersion());
			s3.delete(existing.getId(), BillText.class);
			
			Log.info("Migrated provider-specific bill text " + existing.getId() + " to canonical version " + canonicalTarget.get().getId() + ".");
		}
	}

	protected Optional<BillText> selectCanonicalMigrationTarget(BillText existing, List<BillText> canonicalBillTexts) {
		if (existing == null || canonicalBillTexts == null || canonicalBillTexts.isEmpty()) {
			return Optional.empty();
		}

		val convertedVersion = BillTextIdentity.canonicalCongressVersionFromStoredVersion(existing.getVersion(), existing.getBillId());
		if (convertedVersion.isPresent()) {
			val matchingVersion = canonicalBillTexts.stream()
					.filter(candidate -> StringUtils.equalsIgnoreCase(convertedVersion.get(), candidate.getVersion()))
					.toList();
			if (matchingVersion.size() == 1) {
				return Optional.of(matchingVersion.getFirst());
			}
		}

		val exactTextMatch = matchingCanonicalText(existing, canonicalBillTexts, false);
		if (exactTextMatch.isPresent()) {
			return exactTextMatch;
		}

		val normalizedTextMatch = matchingCanonicalText(existing, canonicalBillTexts, true);
		if (normalizedTextMatch.isPresent()) {
			return normalizedTextMatch;
		}

		if (existing.getLastUpdate() != null) {
			val sameDate = canonicalBillTexts.stream()
					.filter(candidate -> existing.getLastUpdate().equals(candidate.getLastUpdate()))
					.toList();
			if (sameDate.size() == 1) {
				return Optional.of(sameDate.getFirst());
			}
		}

		if (canonicalBillTexts.size() == 1) {
			return Optional.of(canonicalBillTexts.getFirst());
		}

		return Optional.empty();
	}

	private Optional<BillText> matchingCanonicalText(BillText existing, List<BillText> canonicalBillTexts, boolean normalize) {
		String existingText = normalize ? normalizeBillTextForMigration(existing.getText()) : existing.getText();
		if (StringUtils.isBlank(existingText)) {
			return Optional.empty();
		}

		val matches = canonicalBillTexts.stream()
				.filter(candidate -> {
					String candidateText = normalize ? normalizeBillTextForMigration(candidate.getText()) : candidate.getText();
					return StringUtils.equals(existingText, candidateText);
				})
				.toList();

		return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
	}

	private String normalizeBillTextForMigration(String text) {
		if (StringUtils.isBlank(text)) {
			return "";
		}

		return text
				.replace('\u00A0', ' ')
				.replaceAll("\\s+", " ")
				.trim();
	}

	protected void migrateBillInterpretationVersionAliases(String billId, String sourceVersion, String targetVersion) {
		if (StringUtils.isBlank(sourceVersion) || StringUtils.isBlank(targetVersion) || StringUtils.equals(sourceVersion, targetVersion)) {
			return;
		}

		String sessionKey = getSessionKey(billId);
		String objectKey = getVersionedObjectKeyPrefix(billId);
		for (var interp : s3.query(BillInterpretation.class, sessionKey, objectKey)) {
			if (!billId.equals(interp.getBillId()) || !StringUtils.equals(sourceVersion, interp.getSourceBillTextVersion())) {
				continue;
			}

			String targetId = BillInterpretation.generateId(billId, targetVersion, interp.getSliceIndex());
			if (s3.exists(targetId, BillInterpretation.class)) {
				continue;
			}

			BillInterpretation alias = PoliscoreUtil.getObjectMapper().convertValue(interp, BillInterpretation.class);
			alias.setId(targetId);
			alias.setSourceBillTextVersion(targetVersion);
			s3.put(alias);
		}
	}

	private static String getSessionKey(String billId) {
		String[] billIdParts = billId.split("/");
		return billIdParts[1] + "/" + billIdParts[2] + "/" + billIdParts[3];
	}

	private static String getVersionedObjectKeyPrefix(String billId) {
		String[] billIdParts = billId.split("/");
		return billIdParts[4] + "/" + billIdParts[5] + "/";
	}
	
}
