package us.poliscore.tooling;

import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.dataset.LegiscanDatasetProvider;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.legiscan.view.LegiscanBillView;
import us.poliscore.legiscan.view.LegiscanBillTextView;
import us.poliscore.legiscan.view.LegiscanMimeType;
import us.poliscore.legiscan.view.LegiscanTextMetadataView;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;
import us.poliscore.parsing.PDFToText;
import us.poliscore.service.BillService;
import us.poliscore.service.CongressionalBillTextXmlService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name="BillTextPatcher")
public class BillTextPatcher implements QuarkusApplication {

	private static final Logger logger = LoggerFactory.getLogger(BillTextPatcher.class);
	
	@Inject
	private GovernmentDataService data;

	@Inject
	private LocalCachedS3Service s3;

	@Inject
	private BillService billService;

	@Inject
	private CachedLegiscanService legiscan;

	@Inject
	private CongressionalBillTextXmlService congressionalXml;

	private final PDFToText pdfToText = new PDFToText();

	protected void process() {
		long checked = 0;
		long skipped = 0;
		long patched = 0;

		data.importAllDatasets();

		for (var dataset : data.getAllImportedDatasets()) {
			if (LegislativeNamespace.US_CONGRESS.equals(dataset.getNamespace())) {
				continue;
			}

			dataset.optimizeExists(s3, BillText.class);
			List<Bill> bills = dataset.query(Bill.class);
			int processedBills = 0;

			for (Bill bill : bills) {
				LegiscanBillView legiscanBill = bill.getLegiscanId() > 0 ? legiscan.getBill(bill.getLegiscanId()) : null;
				List<LegiscanTextMetadataView> metadata = legiscanBill == null || legiscanBill.getTexts() == null
						? List.of()
						: legiscanBill.getTexts();

				for (BillText existing : billService.getBillTexts(bill)) {
					checked++;
					boolean missingLegiscanId = existing.getLegiscanId() == null;
					boolean missingFormat = existing.getFormat() == null;
					boolean shouldReprocessText = BillTextFormat.TEXT.equals(existing.getFormat());

					if (!missingLegiscanId && !missingFormat && !shouldReprocessText) {
						skipped++;
						continue;
					}

					LegiscanTextMetadataView matchingMetadata = findMatchingMetadata(existing, metadata);
					if (matchingMetadata == null) {
						logger.error("Could not find legiscan metadata for existing bill text [" + existing.getId() + "].");
						skipped++;
						continue;
					}

					try {
						boolean changed = false;

						if (missingLegiscanId) {
							existing.setLegiscanId(matchingMetadata.getDocId());
							changed = true;
						}

						if (missingFormat) {
							existing.setFormat(LegiscanDatasetProvider.getBillTextFormat(matchingMetadata.getMime()));
							changed = true;
						}

						if (shouldReprocessText && LegiscanMimeType.PDF.equals(matchingMetadata.getMime())) {
							LegiscanBillTextView doc = legiscan.getBillText(matchingMetadata.getDocId());
							existing.setText(pdfToText.extract(Base64.getDecoder().decode(doc.getDoc())));
							existing.setFormat(LegiscanDatasetProvider.getBillTextFormat(doc.getMime()));
							changed = true;
						}

						if (!changed) {
							skipped++;
							continue;
						}

						s3.put(existing);
						patched++;
					} catch (Throwable t) {
						logger.error("Exception encountered getting bill text for " + bill.getId(), t);
					}
				}

				processedBills++;
				if (processedBills % 25 == 0) {
					System.out.println("Checked " + processedBills + " bills in dataset " + dataset.getDescription()
							+ "; " + (bills.size() - processedBills) + " bills left.");
				}
			}

			dataset.clearExistsOptimize(s3, BillText.class);
		}

		System.out.println("Program complete.");
		System.out.println("Bill texts checked: " + checked);
		System.out.println("Patched: " + patched);
		System.out.println("Skipped: " + skipped);
	}

	static LegiscanTextMetadataView findMatchingMetadata(BillText billText, List<LegiscanTextMetadataView> metadata) {
		if (billText.getLegiscanId() != null) {
			for (LegiscanTextMetadataView candidate : metadata) {
				if (Objects.equals(billText.getLegiscanId(), candidate.getDocId())) {
					return candidate;
				}
			}
		}

		String version = billText.getVersion();
		if (StringUtils.isNotBlank(version)) {
			for (LegiscanTextMetadataView candidate : metadata) {
				if (StringUtils.equalsIgnoreCase(version, LegiscanDatasetProvider.buildBillTextVersion(candidate))) {
					return candidate;
				}
			}
		}

		return null;
	}

	@Override
	public int run(String... args) throws Exception {
		process();
		Quarkus.waitForExit();
		return 0;
	}

	public static void main(String[] args) {
		Quarkus.run(BillTextPatcher.class, args);
		Quarkus.asyncExit(0);
	}
}
