package us.poliscore.entrypoint.batch;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import com.openai.models.ReasoningEffort;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.bill.BillInterpretationRouter;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.BuildReport;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillPrompt;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.service.BillInterpretationService;
import us.poliscore.service.BillService;
import us.poliscore.service.BillSlicerService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.TokenEstimatorService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name = "BatchBillRequestGenerator")
public class BatchBillRequestGenerator implements QuarkusApplication {
	public static final boolean REPROCESS_INVALID_BILLS = false;

	public static final int MAX_BILL_PROCESS = 5000; // -1 for infinite

//	public static final List<String> specificFetch = null;
	public static final List<String> specificFetch = Arrays.asList(
	Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 1383)
//	Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "hr", 7148),
//	Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "hr", 7567)
);
//public static final List<String> specificFetch = Arrays.asList(Bill.generateId(LegislativeNamespace.US_COLORADO, "2173", "sb", 317));

//public static final List<String> specificFetch = Arrays.asList(
//  "BIL/us/co/2173/hb/1054","BIL/us/co/2173/hb/1062","BIL/us/co/2173/sb/303",
//  "BIL/us/co/2173/hb/1272","BIL/us/co/2173/hb/1069","BIL/us/co/2173/sb/132",
//  "BIL/us/co/2173/sb/201","BIL/us/co/2173/hb/1312","BIL/us/co/2173/hb/1208",
//  "BIL/us/co/2173/sb/11","BIL/us/co/2173/sb/77","BIL/us/co/2173/sb/160"
//);

	public static final List<String> billSkipList = Arrays.asList(
	);
	
	public static final boolean CHECK_S3_EXISTS = specificFetch == null;

	/** Default requested model for these interpretation requests. */
	public static final OpenAIModel billProcessModel = OpenAIModel.DEFAULT_MODEL;

	public static final ReasoningEffort minEffort = ReasoningEffort.LOW;

	@Inject
	private LocalCachedS3Service s3;

	@Inject
	private BillService billService;

	@Inject
	private BillInterpretationService billInterpreter;

	@Inject
	protected PressBillInterpretationRequestGenerator pressBillInterpGenerator;

	@Inject
	private GovernmentDataService data;
	
	@Inject
	private BillSlicerService billSlicer;
	
	@Inject TokenEstimatorService tokenEstimatorService;

	private long totalRequests = 0;

	private final List<InterpretationRequest> requests = new ArrayList<>();

	protected BuildReport report;

	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream()
			.filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt))
			.map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());

	public static void main(String[] args) {
		Quarkus.run(BatchBillRequestGenerator.class, args);
	}

	public List<InterpretationRequest> process() throws IOException {
		data.importAllDatasets();
		return process(data.getBuildDatasets(), new BuildReport(), true, false);
	}

	public List<InterpretationRequest> process(List<PoliscoreDatasetIF> buildDatasets, BuildReport report, boolean enableWebSearch, boolean isSecondFetch) throws IOException {
		this.report = report;
		totalRequests = 0;
		requests.clear();

		Log.info("Generating bill interpretation requests");

		data.importAllDatasets();

		for (val dataset : buildDatasets) {
			dataset.optimizeExists(s3, BillInterpretation.class);
			dataset.optimizeExists(s3, BillText.class);
		}

		for (val dataset : buildDatasets) {
			processDataset(dataset, enableWebSearch, isSecondFetch);
		}

		Log.info("Bill interpretation request generation complete. Generated " + totalRequests + " requests.");

		return new ArrayList<>(requests);
	}

	private void processDataset(PoliscoreDatasetIF dataset, boolean enableWebSearch, boolean isSecondFetch) throws IOException {
		if (MAX_BILL_PROCESS != -1 && isSecondFetch)
			return;
		if (specificFetch != null && isSecondFetch)
			return;

		boolean includePressDirtyBills = !isSecondFetch;

		var stream = dataset.query(Bill.class).stream()
				.filter(b -> specificFetch == null || specificFetch.contains(b.getId()))
				.filter(b -> billService.hasBillText(b));

		if (specificFetch == null) {
			stream = andNotInList(stream, billSkipList);
			stream = andNotAlreadyInterpretedOrInvalid(stream, includePressDirtyBills);
			
			// Texas has a gigantic number of bills. We don't want to do all of them just yet.
			stream = billIsIntroducedAfter(stream, LocalDate.of(2026, 3, 1));

			// stream = ifInterpretedThenInSession(stream, "119");
			// stream = ifInterpretedThenByModel(stream, "gpt-4.1");
			// stream = ifInterpretedThenAboveStatusProgress(stream, 0.1f);
			// stream = ifInterpretedThenBeforeDate(stream, LocalDate.of(2025, 12, 11));
		}

		var requestBills = stream.sorted(Comparator.comparing(Bill::getIntroducedDate).reversed()).toList();

		long total = requestBills.size();

		if (MAX_BILL_PROCESS != -1) {
			requestBills = requestBills.stream().limit(MAX_BILL_PROCESS).toList();
		}

		String outOf = (MAX_BILL_PROCESS != -1 && total > MAX_BILL_PROCESS) ? " out of " + total : "";
		Log.info("Processing " + requestBills.size() + " bills" + outOf + " for request generation on dataset "
				+ dataset.getDescription());

		for (Bill b : requestBills) {

			// The press interpreter may have said this bill was dirty, but after the press
			// interps came back,
			// they came back as NO_INTERP. At this point, it's not actually dirty and
			// doesn't need to be interpreted.
			if (CHECK_S3_EXISTS && billInterpreter.isInterpreted(b.getId()) && includePressDirtyBills
					&& pressBillInterpGenerator.getDirtyBills().contains(b)) {
				val interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class)
						.orElseThrow();

				var s3PressInterps = billService.getPressInterps(interp.getBillId());

				if (!s3PressInterps.stream().anyMatch(s3pi -> !interp.getPressInterps().contains(s3pi)))
					continue;
			}

			val billText = billService.getBillText(b).orElse(null);
			b.setText(billText);
			
			String sBillText = b.getText().getDocument();
			if (StringUtils.isBlank(sBillText)) throw new UnsupportedOperationException("Bill text is empty for " + b.getId());

			String systemMsg = BillPrompt.getPromptForBill(false, enableWebSearch);
			val userMsg = billInterpreter.getUserMsgForBill(b, sBillText, billProcessModel);

			
			if (tokenEstimatorService.estimateTokenCount(systemMsg + "\n" + userMsg) > billProcessModel.getContextWindowTokens()) {

				List<BillSlice> slices = billSlicer.slice(b, b.getText(), billProcessModel);

				if (slices.isEmpty())
					throw new UnsupportedOperationException("Slicer returned zero slices?");
				
				// The XMLBillSlicer returns the document text without the xml nodes, which usually results in bill text about half the size. For this reason, it's possible that the splitter could return a slice of size 1.
				if (slices.size() == 1) {
					if (!StringUtils.isBlank(slices.get(0).getText()))
						sBillText = slices.get(0).getText();

					createRequest(BillInterpretation.generateId(b.getId(), null), b, null,
							systemMsg,
							billInterpreter.getUserMsgForBill(b, sBillText, billProcessModel),
							sBillText);
				} else {
					val sliceInterps = new ArrayList<BillInterpretation>();

					for (int i = 0; i < slices.size(); ++i) {
						BillSlice slice = slices.get(i);

						Optional<BillInterpretation> sliceInterp = s3.get(BillInterpretation.generateId(b.getId(), i),
								BillInterpretation.class);

						if (sliceInterp.isEmpty()) {
							val oid = BillInterpretation.generateId(b.getId(), slice.getSliceIndex());

							if (s3.exists(oid, BillInterpretation.class))
								continue;
							
							// TODO : Does the slicer take the slice prompt size into account?
							createRequest(oid, b, slice.getSliceIndex(), BillPrompt.slicePrompt,
									slice.getText(), slice.getText());
						} else {
							sliceInterps.add(sliceInterp.get());
						}
					}

					if (sliceInterps.size() == slices.size()) {
						systemMsg = BillPrompt.getPromptForBill(true, enableWebSearch);
						List<String> summaries = new ArrayList<>();

						for (int i = 0; i < slices.size(); ++i) {
							summaries.add(sliceInterps.get(i).getLongExplain());
						}

						val oid = BillInterpretation.generateId(b.getId(), null);

						if (CHECK_S3_EXISTS && billInterpreter.isInterpreted(oid))
							continue;

						if (tokenEstimatorService.estimateTokenCount(systemMsg, billInterpreter.getUserMsgForBill(b, String.join("\n", summaries), billProcessModel)) > billProcessModel.getContextWindowTokens()) {
							summaries = new ArrayList<>();
							for (int i = 0; i < slices.size(); ++i) {
								val split = sliceInterps.get(i).getLongExplain().split("\n");
								for (int j = 0; j < Math.min(3, split.length); ++j) {
									summaries.add(split[j]);
								}
							}
						}

						String sliceTexts = String.join("\n", summaries);
						createRequest(oid, b, null, systemMsg,
								billInterpreter.getUserMsgForBill(b, sliceTexts, billProcessModel),
								sliceTexts);
					}
				}
			} else {
				createRequest(BillInterpretation.generateId(b.getId(), null), b, null, systemMsg, userMsg, sBillText);
			}
		}
	}

	private Stream<Bill> andNotInList(Stream<Bill> stream, List<String> billExcludeList) {
		return stream.filter(b -> !billExcludeList.contains(b.getId()));
	}
	
	private Stream<Bill> andNotAlreadyInterpretedOrInvalid(Stream<Bill> stream, boolean includePressDirtyBills) {
		return stream.filter(b -> (!CHECK_S3_EXISTS || !interpretedAndValid(b)
				|| (includePressDirtyBills && pressBillInterpGenerator.getDirtyBills().contains(b))));
	}

	private boolean interpretedAndValid(Bill b) {
		var interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).orElse(null);

		if (REPROCESS_INVALID_BILLS)
			return interp != null && interp.isValid();
		return interp != null;
	}

	private Stream<Bill> ifInterpretedThenByModel(Stream<Bill> stream, String model) {
		return stream.filter(b -> !billInterpreter.isInterpreted(b.getId())
				|| (s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).get().getMetadata()
						.getModel().toLowerCase().equals(model)));
	}

	private Stream<Bill> ifInterpretedThenAboveStatusProgress(Stream<Bill> stream, float progress) {
		return stream.filter(b -> !billInterpreter.isInterpreted(b.getId()) || b.getStatus().getProgress() > progress);
	}

	private Stream<Bill> ifInterpretedThenBeforeDate(Stream<Bill> stream, LocalDate beforeDate) {
		return stream.filter(b -> beforeDate.isAfter(
				s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).get().getDate()));
	}
	
	private Stream<Bill> billIsIntroducedAfter(Stream<Bill> stream, LocalDate beforeDate) {
		return stream.filter(b -> b.getIntroducedDate().isAfter(beforeDate));
	}

	private Stream<Bill> ifInterpretedThenHasZeroSearchReferences(Stream<Bill> stream) {
		return stream.filter(b -> s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).get()
				.getPressInterps().size() == 0);
	}

	private Stream<Bill> ifInterpretedThenInSession(Stream<Bill> stream, String sessionCode) {
		return stream
				.filter(b -> !billInterpreter.isInterpreted(b.getId()) || (b.getSessionCode().equals(sessionCode)));
	}

	private void createRequest(String oid, Bill bill, Integer sliceIndex, String systemMsg, String userMsg, String billText) {
		if (StringUtils.isBlank(billText)) throw new UnsupportedOperationException("Bill text is empty for " + bill.getId() + (sliceIndex != null ? " slice " + sliceIndex : ""));
		
		// Since checking this is now expensive, we will comment it out. We already do these checks before we invoke createRequest so this is redundant anyway.
//		if (billProcessModel.estimateTokenCount(systemMsg + "\n" + userMsg) > billProcessModel.getContextWindowTokens()) {
//			throw new RuntimeException("Max user message length exceeded on " + oid);
//		}

		val req = InterpretationRequest.builder().data(new CustomData(oid)).systemMsg(systemMsg).userMsg(userMsg)
				.requestedModel(billProcessModel).build();
		
		new BillInterpretationRouter().route(req, billProcessModel, OpenAIModel.DEFAULT_MODEL_MINI, bill, billText);

		requests.add(req);
		totalRequests++;
		report.interpretedBills.add(bill);
	}

	@Override
	public int run(String... args) throws Exception {
		List<InterpretationRequest> reqs = process();
		
		long regCount = reqs.stream().filter(r -> !r.getRequestedModel().getId().toLowerCase().contains("mini")).count();
		long miniCount = reqs.stream().filter(r -> r.getRequestedModel().getId().toLowerCase().contains("mini")).count();
		Log.info("Generated " + reqs.size() + " BillInterpretationRequest objects. " + regCount + " are 'regular' models, " + miniCount + " are mini.");
		
		Quarkus.waitForExit();
		return 0;
	}
}
