package us.poliscore.entrypoint;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import net.lingala.zip4j.ZipFile;
import software.amazon.awssdk.utils.StringUtils;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextPublishVersion;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.storage.S3PersistenceService;

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
	
	@Inject private S3PersistenceService s3;
	@Inject private GovernmentDataService data;
	
	@SneakyThrows
	public void process(PoliscoreDataset dataset)
	{
		val store = new File(PoliscoreUtil.APP_DATA, "bill-text");
//		FileUtils.deleteQuietly(store);
		store.mkdirs();
		
		data.importAllDatasets();
		
		s3.optimizeExists(BillText.class, dataset.getSession().getKey());
		
		val congressStore = new File(store, dataset.getSession().getCode());
		congressStore.mkdir();
		
		for (String billType : FETCH_BILL_TYPE)
		{
			val typeStore = new File(congressStore, String.valueOf(billType));
			typeStore.mkdir();
			
			// Download and unzip
			for (int session : new int[] { 1, 2 })
			{
				val url = URL_TEMPLATE.replaceAll("\\{\\{congress\\}\\}", dataset.getSession().getCode())
							.replaceAll("\\{\\{session\\}\\}", String.valueOf(session))
							.replaceAll("\\{\\{type\\}\\}", String.valueOf(billType));
				
				val zip = new File(typeStore, dataset.getSession().getCode() + "-" + billType + ".zip");
				
				// TODO : timestamp code found not working
				if (zip.exists()) { // && new Date().getTime() - zip.lastModified() > 24 * 60 * 60 * 1000
					zip.delete();
				} else if (zip.exists()) { continue; }
				
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
			}
			
			// Upload to S3
			Set<String> processedBills = new HashSet<String>();
			for (File f : PoliscoreUtil.allFilesWhere(typeStore, f -> f.getName().endsWith(".xml")).stream()
					.sorted(Comparator.comparing(File::getName).thenComparing((a,b) -> BillTextPublishVersion.parseFromBillTextName(a.getName()).billMaturityCompareTo(BillTextPublishVersion.parseFromBillTextName(b.getName()))))
					.collect(Collectors.toList()))
			{
				String number = f.getName().replace("BILLS-" + dataset.getSession().getCode() + billType, "").replaceAll("\\D", "");
				val billId = Bill.generateId(dataset.getSession().getNamespace(), dataset.getSession().getCode(), CongressionalBillType.valueOf(billType.toUpperCase()), Integer.parseInt(number));
				
				// TODO : This S3 exists check won't work if there's a new version of the bill text.
				if (!processedBills.contains(billId) && !s3.exists(BillText.generateId(billId), BillText.class))
				{
					try
					{
						val date = parseDate(f);
						
						BillText bt = BillText.factoryFromXml(billId, FileUtils.readFileToString(f, "UTF-8"), date);
						s3.put(bt);
					}
					catch (Throwable t) {
						Log.error("Exception encountered processing " + billId, t);			
					}
					
					processedBills.add(billId);
				}
			}
		}
		
		s3.clearExistsOptimize(BillText.class, dataset.getSession().getKey());
		
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
		
		val text = Arrays.asList(parent.listFiles()).stream()
				.filter(f -> f.getName().contains(bill.getSessionCode() + bill.getType().toLowerCase() + bill.getNumber()))
				.sorted((a,b) -> BillTextPublishVersion.parseFromBillTextName(a.getName()).billMaturityCompareTo(BillTextPublishVersion.parseFromBillTextName(b.getName())))
				.findFirst();
		
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
	
}
