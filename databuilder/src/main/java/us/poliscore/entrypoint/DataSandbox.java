package us.poliscore.entrypoint;


import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.dataset.LegiscanDatasetProvider;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillIssueStat;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.MemoryObjectService;
import us.poliscore.service.SessionInfoService;
import us.poliscore.service.storage.CachedPostgresService;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.LocalFilePersistenceService;

@QuarkusMain(name="DataSandbox")
public class DataSandbox implements QuarkusApplication
{
	private static final String TRACKED_ISSUE_INDEX = "~ti~";
	private static final Map<String, List<Bill>> cachedBills = new HashMap<String, List<Bill>>();

	@Inject
	private MemoryObjectService memService;
	
	@Inject
	private LocalFilePersistenceService localStore;
	
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	private LegislatorService legService;
	
	@Inject
	private BillService billService;
	
	@Inject
	private GovernmentDataService data;

	@Inject
	private CachedPostgresService ddb;
	
	@Inject
	private CachedLegiscanService legiscan;

	@Inject
	private ObjectMapper mapper;
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	protected void process() throws IOException
	{
		data.importAllDatasets();
		
		var arizona = data.getDataset(LegislativeNamespace.US_ARIZONA, 2026);
		var bill = arizona.get(Bill.generateId(arizona.getNamespace(), "2235", "hb", 2589), Bill.class).get();
		
		var legiscanBill = legiscan.getBill(bill.getLegiscanId());
		var texts = legiscanBill.getTexts();
		
		for (var text : texts) {
			System.out.println(LegiscanDatasetProvider.buildBillTextVersion(text) + " : " + text.getMimeCode());
		}
		
		System.out.println("Program Complete");
	}
	
	public static void main(String[] args) {
		Quarkus.run(DataSandbox.class, args);
		Quarkus.asyncExit(0);
	}
	
	@Override
    public int run(String... args) throws Exception {
        process();
        
        Quarkus.waitForExit();
        return 0;
    }
}
