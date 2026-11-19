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
import us.poliscore.model.bill.BillTextOrder;
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
		LocalDateTime interpretationUpdate = interp.getLastUpdate();
		if (interpretationUpdate == null && interp.getMetadata() != null && interp.getMetadata().getDate() != null) {
			interpretationUpdate = interp.getMetadata().getDate().atStartOfDay();
		}
		LocalDateTime billLastAction = b.getLastActionDate() == null ? null : b.getLastActionDate().atStartOfDay();
		if (interpretationUpdate != null && (billLastAction == null || interpretationUpdate.isAfter(billLastAction))) {
			billLastAction = interpretationUpdate;
		}
		var existingBillLastUpdate = b.getLastUpdate();
		if (billLastAction != null && (existingBillLastUpdate == null || billLastAction.isAfter(existingBillLastUpdate))) {
			b.setLastUpdate(billLastAction);
		}
		
		var billInterpretations = new ArrayList<>(getBillInterpretations(b).stream()
				.filter(existingInterp -> Objects.equals(existingInterp.getSliceIndex(), null))
				.filter(existingInterp -> !Objects.equals(existingInterp.getId(), interp.getId()))
				.toList());
		billInterpretations.forEach(this::populatePressInterps);
		populatePressInterps(interp);
		billInterpretations.add(interp);
        var sources = getBillTexts(b).stream().map(BillText::metadataOnly).toList();
        for (var source : sources) {
            billInterpretations.stream().map(BillInterpretation::getFunding)
                    .filter(snapshot -> snapshot != null && snapshot.appliesTo(source))
                    .max(Comparator.comparingLong(us.poliscore.model.bill.FundingSnapshot::computedAt))
                    .ifPresent(source::setFunding);
        }
        b.setTexts(sources);
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
		return getInterpretation(bill.getId(), sliceIndex);
	}

	public Optional<BillInterpretation> getInterpretation(Bill bill, BillText preferredText, Integer sliceIndex) {
		return Bill.selectInterpretation(getBillInterpretations(bill), preferredText == null ? null : preferredText.getVersion(), sliceIndex);
	}

	public Optional<BillInterpretation> getInterpretation(String billId) {
		return getInterpretation(billId, null);
	}

	public Optional<BillInterpretation> getInterpretation(String billId, Integer sliceIndex) {
		var candidates = getBillInterpretations(billId).stream()
				.filter(candidate -> Objects.equals(sliceIndex, candidate.getSliceIndex())).toList();
		if (candidates.size() < 2) return candidates.stream().findFirst();
		String version = getBillText(billId).map(BillText::getVersion).orElse(null);
		return Bill.selectInterpretation(candidates, version, sliceIndex);
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
		return getBillText(bill.getId());
	}

	private Optional<BillText> getBillText(String billId) {
		val op = getBillTexts(billId).stream().max(BillTextOrder.ASCENDING);

		if (op.isPresent())
			return op;

		// Legacy bill text objects were stored at the bill's unversioned key. Keep
		// them readable while datasets are migrated, but prefer versioned text when
		// both representations exist.
		return s3.get(BillText.generateId(billId), BillText.class);
	}
    
    public boolean hasBillText(Bill bill)
    {
    	return s3.exists(BillText.generateId(bill.getId()), BillText.class)
    			|| s3.existsByPrefix(BillText.class, getSessionKey(bill.getId()), getVersionedObjectKeyPrefix(bill.getId()));
    }
    
    public List<BillText> getBillTexts(Bill bill) {
        return getBillTexts(bill.getId());
    }

    protected List<BillText> getBillTexts(String billId) {
        String sessionKey = getSessionKey(billId);
        String objectKey = getVersionedObjectKeyPrefix(billId);

        List<BillText> billTexts = s3.query(BillText.class, sessionKey, objectKey).stream()
                .filter(bt -> billId.equals(bt.getBillId()))
                .collect(Collectors.toList());

        List<String> missingDateIds = billTexts.stream()
                .filter(bt -> bt.getLastUpdate() == null)
                .map(bt -> "billTextId=" + bt.getId() + ", version=" + bt.getVersion())
                .collect(Collectors.toList());

        if (!missingDateIds.isEmpty()) {
            throw new IllegalStateException(
                    "Bill texts did not have a date for bill " + billId + ": "
                    + String.join("; ", missingDateIds));
        }

        return billTexts.stream()
                .sorted(BillTextOrder.ASCENDING)
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
