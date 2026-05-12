package us.poliscore.tooling;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.legiscan.view.LegiscanBillView;
import us.poliscore.legiscan.view.LegiscanBillTextView;
import us.poliscore.legiscan.view.LegiscanMimeType;
import us.poliscore.legiscan.view.LegiscanTextMetadataView;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillText;
import us.poliscore.parsing.PDFToText;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;

@QuarkusMain(name = "TikaLegiscanPdfTextProbe")
public class TikaLegiscanPdfTextProbe implements QuarkusApplication {

	@Inject
	GovernmentDataService data;

	@Inject
	BillService billService;

	@Inject
	CachedLegiscanService legiscan;

	private final PDFToText pdfToText = new PDFToText();

	public static void main(String[] args) {
		Quarkus.run(TikaLegiscanPdfTextProbe.class, args);
		Quarkus.asyncExit(0);
	}

	@Override
	public int run(String... args) throws Exception {
		ProbeArgs probeArgs = ProbeArgs.parse(args);
		data.importAllDatasets();

		PoliscoreDatasetIF dataset = data.getDataset(probeArgs.namespace(), probeArgs.year());
		Bill bill = dataset.get(Bill.generateId(dataset.getNamespace(), probeArgs.sessionCode(), probeArgs.billType(), probeArgs.billNumber()), Bill.class)
				.orElseThrow(() -> new IllegalArgumentException("Bill not found for " + probeArgs));

		BillText selected = selectBillText(bill, probeArgs.version())
				.orElseThrow(() -> new IllegalArgumentException("No bill text found for " + bill.getId()));

		LegiscanTextMetadataView matchingMetadata = resolveLegiscanMetadata(bill, selected);
		LegiscanBillTextView doc = legiscan.getBillText(matchingMetadata.getDocId());

		System.err.println("Bill: " + bill.getId());
		System.err.println("Selected version: " + selected.getVersion());
		System.err.println("LegiScan doc_id: " + doc.getDocId());
		System.err.println("LegiScan MIME: " + doc.getMimeCode());
		System.err.println("---- extracted text follows ----");

		System.out.println(extractText(doc));

		Quarkus.waitForExit();
		return 0;
	}

	private Optional<BillText> selectBillText(Bill bill, String requestedVersion) {
		if (requestedVersion != null && !requestedVersion.isBlank()) {
			return billService.getBillTexts(bill).stream()
					.filter(text -> requestedVersion.equalsIgnoreCase(text.getVersion()))
					.findFirst();
		}

		return billService.getBillText(bill);
	}

	private LegiscanTextMetadataView resolveLegiscanMetadata(Bill bill, BillText selected) {
		LegiscanBillView legiscanBill = bill.getLegiscanId() > 0 ? legiscan.getBill(bill.getLegiscanId()) : null;
		List<LegiscanTextMetadataView> metadata = legiscanBill == null || legiscanBill.getTexts() == null
				? List.of()
				: legiscanBill.getTexts();

		LegiscanTextMetadataView matchingMetadata = BillTextPatcher.findMatchingMetadata(selected, metadata);
		if (matchingMetadata == null) {
			throw new IllegalArgumentException("Could not find LegiScan metadata for bill text [" + selected.getId() + "].");
		}

		return matchingMetadata;
	}

	private String extractText(LegiscanBillTextView doc) throws Exception {
		byte[] bytes = Base64.getDecoder().decode(doc.getDoc());

		if (!LegiscanMimeType.PDF.equals(doc.getMime())) {
			System.err.println("Document is not a PDF; printing decoded text bytes directly.");
			return new String(bytes, StandardCharsets.UTF_8);
		}

		return pdfToText.extract(bytes);
	}

	private record ProbeArgs(
			LegislativeNamespace namespace,
			int year,
			String sessionCode,
			String billType,
			int billNumber,
			String version) {
		
		static ProbeArgs parse(String[] args) {
			String namespace = value(args, 0, "us/nm");
			int year = Integer.parseInt(value(args, 1, "2026"));
			String sessionCode = value(args, 2, "2251");
			String billType = value(args, 3, "sb");
			int billNumber = Integer.parseInt(value(args, 4, "100"));
			String version = value(args, 5, null);
			return new ProbeArgs(LegislativeNamespace.of(namespace), year, sessionCode, billType, billNumber, version);
		}

		private static String value(String[] args, int index, String defaultValue) {
			if (args == null || args.length <= index || args[index] == null || args[index].isBlank()) {
				return defaultValue;
			}
			return args[index];
		}

		@Override
		public String toString() {
			return namespace.getNamespace() + " " + year + " " + sessionCode + " " + billType + " " + billNumber
					+ (version == null ? "" : " " + version);
		}
	}
}
