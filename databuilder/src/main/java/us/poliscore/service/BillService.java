package us.poliscore.service;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillIssueStat;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.service.storage.DynamoDbPersistenceService;
import us.poliscore.service.storage.LocalCachedS3Service;

@ApplicationScoped
@Priority(4)
public class BillService {
	
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	protected LegislatorService lService;
	
	@Inject
	private DynamoDbPersistenceService ddb;
	
	@Inject
	private GovernmentDataService data;
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	public void populatePressInterps(BillInterpretation interp)
	{
		var pressInterps = s3.query(PressInterpretation.class, interp.getBillId().replace(Bill.ID_CLASS_PREFIX + "/", ""));
		
//		pressInterps = pressInterps.stream().filter(i -> i.getBillId().equals(interp.getBillId()) && !InterpretationOrigin.POLISCORE.equals(i.getOrigin()) && !i.isNoInterp()).collect(Collectors.toList());
		
		pressInterps = pressInterps.stream()
			    .filter(i -> {
			        try {
			            return i.getBillId().equals(interp.getBillId())
			                && !InterpretationOrigin.POLISCORE.equals(i.getOrigin())
			                && !i.isNoInterp();
			        } catch (Exception e) {
			            Log.warn("Skipping press interpretation due to error: " + i, e);
			            return false; // skip this item if it errors
			        }
			    })
			    .collect(Collectors.toList());

		
		interp.setPressInterps(pressInterps);
	}

	public void ddbPersist(Bill b, BillInterpretation interp)
	{
		populatePressInterps(interp);
		b.setInterpretation(interp);
		ddb.put(b);
		
		for(TrackedIssue issue : TrackedIssue.values()) {
			ddb.put(new BillIssueStat(issue, b.getImpact(issue), b));
		}
	}
	
	public List<PressInterpretation> getPressInterps(String billId) {
		return getPressInterps(billId, true);
	}
	
	public List<PressInterpretation> getPressInterps(String billId, boolean excludeNoInterps)
	{
		String sessionKey = billId.substring(StringUtils.ordinalIndexOf(billId, "/", 1)+1, StringUtils.ordinalIndexOf(billId, "/", 4));
		String objectKey = billId.substring(StringUtils.ordinalIndexOf(billId, "/", 4)+1);
		
		var s3PressInterps = s3.query(PressInterpretation.class, sessionKey, objectKey).stream().filter(i -> !InterpretationOrigin.POLISCORE.equals(i.getOrigin()) && (!excludeNoInterps || !i.isNoInterp())).collect(Collectors.toList());
		
		return s3PressInterps;
	}
    
    protected String generateBillName(String url)
    {
    	return generateBillName(url, -1);
    }
    
    @SneakyThrows
    protected String generateBillName(String url, int sliceIndex)
    {
		URI uri = new URI(url);
		String path = uri.getPath();
		String billName = path.substring(path.lastIndexOf('/') + 1);
		
		if (billName.contains("."))
		{
			billName = billName.substring(0, billName.lastIndexOf("."));
		}
		
		if (billName.contains("BILLS-"))
		{
			billName = billName.replace("BILLS-", "");
		}
		
		if (sliceIndex != -1)
		{
			billName += "-" + String.valueOf(sliceIndex);
		}

		return billName;
    }
    
    @SneakyThrows
	public Optional<BillText> getBillText(Bill bill)
	{
//		val parent = new File(PoliscoreUtil.APP_DATA, "bill-text/" + bill.getCongress() + "/" + bill.getType());
//		
//		val text = Arrays.asList(parent.listFiles()).stream()
//				.filter(f -> f.getName().contains(bill.getCongress() + bill.getType().getName().toLowerCase() + bill.getNumber()))
//				.sorted((a,b) -> BillTextPublishVersion.parseFromBillTextName(a.getName()).billMaturityCompareTo(BillTextPublishVersion.parseFromBillTextName(b.getName())))
//				.findFirst();
//		
//		if (text.isPresent())
//		{
//			return Optional.of(PoliscoreUtil.getObjectMapper().readValue(FileUtils.readFileToString(text.get(), "UTF-8"), BillText.class));
//		}
//		else
//		{
//			return Optional.empty();
//		}
    	
    	return s3.get(BillText.generateId(bill.getId()), BillText.class);
	}
    
    public boolean hasBillText(Bill bill)
    {
    	return s3.exists(BillText.generateId(bill.getId()), BillText.class);
    }
}
