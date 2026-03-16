package us.poliscore.entrypoint;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;

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
import software.amazon.awssdk.utils.StringUtils;
import us.poliscore.PoliscoreUtil;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextPublishVersion;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.storage.CachedS3Service;

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
	
	public static List<String> FETCH_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	@Inject private CachedS3Service s3;
	@Inject private GovernmentDataService data;
	
	@SneakyThrows
	public void process(PoliscoreDatasetIF dataset)
	{
		val store = new File(PoliscoreUtil.APP_DATA, "bill-text");
//		FileUtils.deleteQuietly(store);
		store.mkdirs();
		
		data.importAllDatasets();
		
		dataset.optimizeExists(s3, BillText.class);
		
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
	protected LocalDate parseDate(File f)
	{
		val text = Jsoup.parse(f).select("bill dublinCore dc|date").text();
		
		if (StringUtils.isBlank(text)) return null;
		
		return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
	}
	
	@SneakyThrows
	public Optional<String> getBillText(Bill bill)
	{
		val parent = new File(PoliscoreUtil.APP_DATA, "bill-text/" + bill.getSessionCode() + "/" + bill.getType());
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
		
		migrateLegacyBillText(billId, billTexts);
	}
	
	protected String buildBillId(PoliscoreDatasetIF dataset, String billType, File f) {
		String number = f.getName().replace("BILLS-" + dataset.getCode() + billType, "").replaceAll("\\D", "");
		return Bill.generateId(dataset.getNamespace(), dataset.getCode(), CongressionalBillType.valueOf(billType.toUpperCase()), Integer.parseInt(number));
	}
	
	@SneakyThrows
	protected BillText buildBillText(String billId, File file) {
		BillTextPublishVersion version = BillTextPublishVersion.parseFromBillTextName(file.getName());
		return BillText.factory(billId, FileUtils.readFileToString(file, "UTF-8"), parseDate(file), version, us.poliscore.model.bill.BillTextFormat.XML);
	}
	
	protected void upsertVersionedBillText(BillText candidate) {
		if (s3.exists(candidate.getId(), BillText.class)) return;
		
		s3.put(candidate);
	}
	
	protected void migrateLegacyBillText(String billId, List<BillText> versionedBillTexts) {
		val legacyId = BillText.generateId(billId);
		
		if (!s3.exists(legacyId, BillText.class)) return;
		
		// The versioned GPO upload already happened above, so we only need to retire the legacy key.
		s3.delete(legacyId, BillText.class);
		Log.info("Migrated legacy bill text " + legacyId + " to versioned key.");
	}
	
}
