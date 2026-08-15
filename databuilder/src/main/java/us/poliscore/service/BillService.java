package us.poliscore.service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillIssueStat;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.ObjectStorageServiceIF;

@ApplicationScoped
@Priority(4)
public class BillService {
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	protected LegislatorService lService;
	
	@Inject
	private GovernmentDataService data;
	
	protected Comparator<BillText> getBillTextComparator(Bill bill) {
		return Comparator.comparing(BillText::getLastUpdate);
	}
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	public void populatePressInterps(BillInterpretation interp)
	{
		interp.setPressInterps(getPressInterps(interp.getBillId()).stream()
				.map(this::trimForBillPayload)
				.toList());
	}

	public void persist(Bill b, BillInterpretation interp, ObjectStorageServiceIF store)
	{
		persist(b, interp, store, true);
	}

	public void persist(Bill b, BillInterpretation interp, ObjectStorageServiceIF store, boolean populateIssueStats)
	{
		applyInterpretation(b, interp);
		store.put(b);
	}

	public void applyInterpretation(Bill b, BillInterpretation interp)
	{
		var billLastAction = b.getLastActionDate() == null || interp.getLastUpdate().isAfter(b.getLastActionDate().atStartOfDay()) ? interp.getLastUpdate() : b.getLastActionDate().atStartOfDay();
		var existingBillLastUpdate = b.getLastUpdate();
		b.setLastUpdate(existingBillLastUpdate != null && existingBillLastUpdate.isAfter(billLastAction) ? existingBillLastUpdate : billLastAction);
		
		b.setTexts(getBillTexts(b));
		var billInterpretations = new ArrayList<>(getBillInterpretations(b).stream()
				.filter(existingInterp -> Objects.equals(existingInterp.getSliceIndex(), null))
				.filter(existingInterp -> !Objects.equals(existingInterp.getId(), interp.getId()))
				.toList());
		billInterpretations.forEach(this::populatePressInterps);
		populatePressInterps(interp);
		billInterpretations.add(interp);
		b.setInterpretations(billInterpretations);
	}

	public String getBillInterpretationId(Bill bill) {
		return getBillInterpretationId(bill, null);
	}

	public String getBillInterpretationId(Bill bill, Integer sliceIndex) {
		String billTextVersion = getBillText(bill).map(BillText::getVersion).orElse(null);
		return BillInterpretation.generateId(bill.getId(), billTextVersion, sliceIndex);
	}

	public SortedSet<BillInterpretation> getBillInterpretations(Bill bill) {
		return getBillInterpretations(bill.getId());
	}

	/**
	 * Returns all interpretations for the given bill, sorted by interpretation date
	 * in ascending order. The newest interpretation will therefore be the last
	 * element in the returned set.
	 */
	public SortedSet<BillInterpretation> getBillInterpretations(String billId) {
		return s3.query(BillInterpretation.class, getSessionKey(billId), getBillInterpretationObjectKeyPrefix(billId)).stream()
				.filter(interp -> billId.equals(interp.getBillId()))
				.collect(Collectors.toCollection(() -> new TreeSet<>(getBillInterpretationComparator())));
	}

	public Optional<BillInterpretation> getInterpretation(Bill bill) {
		return getInterpretation(bill, null);
	}

	public Optional<BillInterpretation> getInterpretation(Bill bill, Integer sliceIndex) {
		BillInterpretation newestMatch = null;

		// Interpretations are sorted in ascending date order, so the last matching
		// interpretation is the newest available one.
		for (BillInterpretation interp : getBillInterpretations(bill)) {
			if (Objects.equals(sliceIndex, interp.getSliceIndex())) {
				newestMatch = interp;
			}
		}

		return Optional.ofNullable(newestMatch);
	}

	public Optional<BillInterpretation> getInterpretation(String billId) {
		return getInterpretation(billId, null);
	}

	public Optional<BillInterpretation> getInterpretation(String billId, Integer sliceIndex) {
		BillInterpretation newestMatch = null;

		for (BillInterpretation interp : getBillInterpretations(billId)) {
			if (Objects.equals(sliceIndex, interp.getSliceIndex())) {
				newestMatch = interp;
			}
		}

		return Optional.ofNullable(newestMatch);
	}
	
	public List<PressInterpretation> getPressInterps(String billId) {
		return getPressInterps(billId, true);
	}
	
	public List<PressInterpretation> getPressInterps(String billId, boolean excludeNoInterps)
	{
		String sessionKey = billId.substring(StringUtils.ordinalIndexOf(billId, "/", 1)+1, StringUtils.ordinalIndexOf(billId, "/", 4));
		String objectKey = billId.substring(StringUtils.ordinalIndexOf(billId, "/", 4)+1);
		
		var s3PressInterps = s3.query(PressInterpretation.class, sessionKey, objectKey).stream()
				.filter(i -> i.getBillId().equals(billId) && !InterpretationOrigin.POLISCORE.equals(i.getOrigin()) && (!excludeNoInterps || !i.isNoInterp()))
				.collect(Collectors.toList());
		
		return s3PressInterps;
	}

	private PressInterpretation trimForBillPayload(PressInterpretation interp) {
		var copy = new PressInterpretation();
		copy.setId(interp.getId());
		copy.setBillId(interp.getBillId());
		copy.setOrigin(interp.getOrigin());
		copy.setMetadata(interp.getMetadata());
		copy.setGenArticleTitle(interp.getGenArticleTitle());
		copy.setShortExplain(interp.getShortExplain());
//		copy.setLongExplain(interp.getLongExplain());
		copy.setSentimentText(interp.getSentimentText());
		copy.setAuthor(interp.getAuthor());
		copy.setType(interp.getType());
		copy.setConfidence(interp.getConfidence());
		copy.setSentiment(interp.getSentiment());
		copy.setNoInterp(interp.isNoInterp());
		copy.setLastUpdate(interp.getLastUpdate());
		copy.setStorageBucket(interp.getStorageBucket());
		return copy;
	}
	
	public void wipeAllPressInterps(Bill b)
	{
		var pressInterps = getPressInterps(b.getId(), false);
		
		for (val interp : pressInterps)
		{
			s3.delete(interp.getId(), PressInterpretation.class);
		}
		
		Log.info("Deleted " + pressInterps.size() + " existing interpretations for bill " + b.getId());
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
    	
		val op = getBillTexts(bill).stream().max(getBillTextComparator(bill));

		if (op.isPresent())
			return op;

		// Legacy bill text objects were stored at the bill's unversioned key. Keep
		// them readable while datasets are migrated, but prefer versioned text when
		// both representations exist.
		return s3.get(BillText.generateId(bill.getId()), BillText.class);
	}
    
    public boolean hasBillText(Bill bill)
    {
    	return s3.exists(BillText.generateId(bill.getId()), BillText.class)
    			|| s3.existsByPrefix(BillText.class, getSessionKey(bill.getId()), getVersionedObjectKeyPrefix(bill.getId()));
    }
    
    public List<BillText> getBillTexts(Bill bill) {
        String sessionKey = getSessionKey(bill.getId());
        String objectKey = getVersionedObjectKeyPrefix(bill.getId());

        List<BillText> billTexts = s3.query(BillText.class, sessionKey, objectKey).stream()
                .filter(bt -> bill.getId().equals(bt.getBillId()))
                .collect(Collectors.toList());

        List<String> missingDateIds = billTexts.stream()
                .filter(bt -> bt.getLastUpdate() == null)
                .map(bt -> "billTextId=" + bt.getId() + ", version=" + bt.getVersion())
                .collect(Collectors.toList());

        if (!missingDateIds.isEmpty()) {
            throw new IllegalStateException(
                    "Bill texts did not have a date for bill " + bill.getId() + ": "
                    + String.join("; ", missingDateIds));
        }

        return billTexts.stream()
                .sorted(getBillTextComparator(bill))
                .collect(Collectors.toList());
    }

    private Comparator<BillInterpretation> getBillInterpretationComparator() {
    	return Comparator.comparing(this::getBillInterpretationSortDate)
    			.thenComparing(BillInterpretation::getId);
    }

    private LocalDateTime getBillInterpretationSortDate(BillInterpretation interpretation) {
    	if (interpretation.getLastUpdate() != null) {
    		return interpretation.getLastUpdate();
    	}

    	if (interpretation.getMetadata() != null && interpretation.getMetadata().getDate() != null) {
    		return interpretation.getMetadata().getDate().atStartOfDay();
    	}

    	return LocalDateTime.MIN;
    }
    
    protected String getSessionKey(String billId) {
    	return billId.substring(StringUtils.ordinalIndexOf(billId, "/", 1)+1, StringUtils.ordinalIndexOf(billId, "/", 4));
    }

    protected String getBillInterpretationObjectKeyPrefix(String billId) {
    	return billId.substring(StringUtils.ordinalIndexOf(billId, "/", 4)+1);
    }
    
    protected String getVersionedObjectKeyPrefix(String billId) {
    	return billId.substring(StringUtils.ordinalIndexOf(billId, "/", 4)+1) + "/";
    }
}
