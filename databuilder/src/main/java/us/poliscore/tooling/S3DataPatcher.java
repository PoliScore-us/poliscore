package us.poliscore.tooling;


import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.legiscan.view.LegiscanBillTextView;
import us.poliscore.legiscan.view.LegiscanBillView;
import us.poliscore.legiscan.view.LegiscanTextMetadataView;
import us.poliscore.legiscan.view.LegiscanTextType;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextPublishVersion;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name="S3DataPatcher")
public class S3DataPatcher implements QuarkusApplication {
	
	@Inject
	private GovernmentDataService data;
	
	@Inject
	private LocalCachedS3Service s3;

	@Inject
	private BillService billService;
	
	@Inject
	protected CachedLegiscanService legiscan;
	
	protected void process() throws IOException
	{
		long count = 0;
		long skipped = 0;
		
		data.importAllDatasets();
		
		for (var dataset : data.getBuildDatasets()) {
			dataset.optimizeExists(s3, BillInterpretation.class);
			
			for (var bill : dataset.query(Bill.class)) {
				if (!patchIntroducedBillTextDates(bill)) continue;
				
				List<BillText> billTexts = billService.getBillTexts(bill);
				if (billTexts.isEmpty()) {
					skipped++;
					continue;
				}
				
				for (var interp : getBillInterpretations(bill)) {
					if (interp.getSourceBillTextVersion() != null) {
						skipped++;
						continue;
					}
					
					BillText matchedText = findBillTextForInterpretation(billTexts, interp);
					if (matchedText == null) {
						skipped++;
						continue;
					}
					
					interp.setSourceBillTextVersion(matchedText.getVersion());
					s3.put(interp);
					count++;
				}
			}
		}
		
		System.out.println("Program complete. Patched " + count + " interpretations and skipped " + skipped + ".");
	}

	private boolean patchIntroducedBillTextDates(Bill bill) {
		LegiscanBillView legiscanBill = null;
		
		boolean valid = true;

		for (var billText : s3.query(BillText.class, getSessionKey(bill.getId()), getBillTextObjectKeyPrefix(bill.getId()))) {
			if (!bill.getId().equals(billText.getBillId())) {
				continue;
			}
			if (billText.getLastUpdate() != null) {
				continue;
			}
			
			LocalDate billTextDate = null;
			
			if (legiscanBill == null && bill.getLegiscanId() > 0) {
				legiscanBill = legiscan.getBill(bill.getLegiscanId());
			}

			billTextDate = resolveLegiscanBillTextDate(bill, billText, legiscanBill);
			if (billTextDate != null) {
				billText.setLastUpdated(billTextDate);
			}

			if (billText.getLastUpdate() == null && isIntroducedBillTextVersion(billText.getVersion()) && bill.getIntroducedDate() != null) {
				billText.setLastUpdated(bill.getIntroducedDate());
			}
			
			if (billText.getLastUpdate() != null) {
				s3.put(billText);
			} else {
				s3.delete(billText.getId(), BillText.class);
				valid = false;
			}
		}
		
		return valid;
	}

	private LocalDate resolveLegiscanBillTextDate(Bill bill, BillText billText, LegiscanBillView legiscanBill) {
		if (legiscanBill == null || legiscanBill.getTexts() == null || legiscanBill.getTexts().isEmpty()) {
			return null;
		}

		LegiscanTextMetadataView metadata = findMatchingLegiscanTextMetadata(billText, legiscanBill.getTexts());
		if (metadata == null) {
			return null;
		}

		if (metadata.getDocId() != null) {
			billText.setLegiscanId(metadata.getDocId());
		}

		LocalDate date = metadata.getDate();
		if (date == null && metadata.getDocId() != null) {
			LegiscanBillTextView doc = legiscan.getBillText(metadata.getDocId());
			date = doc.getDate();
		}
		if (date == null && isIntroducedBillText(metadata)) {
			date = bill.getIntroducedDate();
		}

		return date;
	}

	private LegiscanTextMetadataView findMatchingLegiscanTextMetadata(BillText billText, List<LegiscanTextMetadataView> textMetadata) {
		if (billText.getLegiscanId() != null) {
			for (var metadata : textMetadata) {
				if (Objects.equals(billText.getLegiscanId(), metadata.getDocId())) {
					return metadata;
				}
			}
		}

		Integer docId = parseDocIdFromVersion(billText.getVersion());
		if (docId != null) {
			for (var metadata : textMetadata) {
				if (Objects.equals(docId, metadata.getDocId())) {
					return metadata;
				}
			}
		}

		return null;
	}

	private List<BillInterpretation> getBillInterpretations(Bill bill) {
		return s3.query(BillInterpretation.class, getSessionKey(bill.getId()), getObjectKeyPrefix(bill.getId())).stream()
				.filter(interp -> bill.getId().equals(interp.getBillId()))
				.toList();
	}

	private BillText findBillTextForInterpretation(List<BillText> billTexts, BillInterpretation interp) {
		LocalDateTime interpretationTime = interp.getLastUpdate();
		if (interpretationTime == null) {
			return billTexts.stream().max(BILL_TEXT_PATCH_ORDER).orElse(null);
		}
		
		return billTexts.stream()
				.filter(text -> text.getLastUpdate() != null && !text.getLastUpdate().isAfter(interpretationTime))
				.max(BILL_TEXT_PATCH_ORDER)
				.orElse(null);
	}

	private String getSessionKey(String billId) {
		return billId.substring(StringUtils.ordinalIndexOf(billId, "/", 1) + 1, StringUtils.ordinalIndexOf(billId, "/", 4));
	}

	private String getObjectKeyPrefix(String billId) {
		return billId.substring(StringUtils.ordinalIndexOf(billId, "/", 4) + 1);
	}

	private String getBillTextObjectKeyPrefix(String billId) {
		return getObjectKeyPrefix(billId) + "/";
	}

	private boolean isIntroducedBillTextVersion(String version) {
		if (StringUtils.isBlank(version)) {
			return false;
		}

		String normalized = version.trim().toUpperCase();
		return normalized.startsWith("INTRODUCED")
				|| BillTextPublishVersion.IH.name().equals(normalized)
				|| BillTextPublishVersion.IS.name().equals(normalized);
	}

	private boolean isIntroducedBillText(LegiscanTextMetadataView metadata) {
		if (metadata == null || metadata.getTypeId() == null) {
			return false;
		}

		try {
			return LegiscanTextType.INTRODUCED.equals(metadata.getType());
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	private Integer parseDocIdFromVersion(String version) {
		if (StringUtils.isBlank(version)) {
			return null;
		}

		String suffix = StringUtils.substringAfterLast(version, "-");
		if (!StringUtils.isNumeric(suffix)) {
			return null;
		}

		return Integer.valueOf(suffix);
	}

	private static final Comparator<BillText> BILL_TEXT_PATCH_ORDER = Comparator
			.comparing(BillText::getLastUpdate)
			.thenComparing(text -> billTextVersionRank(text.getVersion()));

	private static Integer billTextVersionRank(String version) {
		if (version == null) {
			return -1;
		}
		
		try {
			return BillTextPublishVersion.valueOf(version.toUpperCase()).ordinal();
		} catch (IllegalArgumentException ex) {
			return -1;
		}
	}
	
	@Override
	public int run(String... args) throws Exception {
	  process();
	  
	  Quarkus.waitForExit();
	  return 0;
	}
	
	public static void main(String[] args) {
		Quarkus.run(S3DataPatcher.class, args);
		Quarkus.asyncExit(0);
	}
}
