package us.poliscore.entrypoint.batch;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import com.openai.models.ReasoningEffort;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.Data;
import lombok.val;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.bill.BillInterpretationRouter;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.BuildReport;
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
	
	@Data
	public static class BillGenerationCriteria {
		private final List<BiFunction<BatchBillRequestGenerator, Stream<Bill>, Stream<Bill>>> refinements = new ArrayList<>();
		
		public boolean enableWebSearch = true;
		
		public boolean REPROCESS_INVALID_BILLS = false;
	
		public int MAX_BILL_PROCESS = 5000; // -1 for infinite
		
		public List<String> billSkipList = Arrays.asList(
		);
		
		public boolean CHECK_S3_EXISTS = true;
	
		/** Default requested model for these interpretation requests. */
		public OpenAIModel billProcessModel = OpenAIModel.DEFAULT_MODEL;
	
		public ReasoningEffort minEffort = ReasoningEffort.LOW;
		
		public static BillGenerationCriteria defaultCriteria() {
			return new BillGenerationCriteria()
					.billIsIntroducedAfter(LocalDate.of(2026, 3, 1));
		}

		public Stream<Bill> refine(Stream<Bill> stream, BatchBillRequestGenerator generator, boolean includePressDirtyBills) {
			stream = stream
					.filter(generator.billService::hasBillText);

			stream = generator.andNotInList(stream, billSkipList);
			stream = generator.andNotAlreadyInterpreted(this, stream, includePressDirtyBills);

			for (val refinement : refinements) {
				stream = refinement.apply(generator, stream);
			}

			return stream;
		}

		public BillGenerationCriteria ifInterpretedThenByModel(String model) {
			refinements.add((generator, stream) -> stream.filter(b -> !generator.billInterpreter.isInterpreted(b.getId())
					|| StringUtils.equalsIgnoreCase(
							generator.requireInterpretation(b).getMetadata().getModel(),
							model)));
			return this;
		}

		public BillGenerationCriteria ifInterpretedThenAboveStatusProgress(float progress) {
			refinements.add((generator, stream) -> stream.filter(b -> !generator.billInterpreter.isInterpreted(b.getId())
					|| b.getStatus().getProgress() > progress));
			return this;
		}

		public BillGenerationCriteria ifInterpretedThenBeforeDate(LocalDate beforeDate) {
			refinements.add((generator, stream) -> stream.filter(b -> !generator.billInterpreter.isInterpreted(b.getId())
					|| beforeDate.isAfter(generator.requireInterpretation(b).getDate())));
			return this;
		}

		public BillGenerationCriteria billIsIntroducedAfter(LocalDate afterDate) {
			refinements.add((generator, stream) -> stream.filter(b -> b.getIntroducedDate().isAfter(afterDate)));
			return this;
		}

		public BillGenerationCriteria ifInterpretedThenHasZeroSearchReferences() {
			refinements.add((generator, stream) -> stream.filter(b -> !generator.billInterpreter.isInterpreted(b.getId())
					|| generator.requireInterpretation(b).getPressInterps().size() == 0));
			return this;
		}

		public BillGenerationCriteria ifInterpretedThenInSession(String sessionCode) {
			refinements.add((generator, stream) -> stream.filter(b -> !generator.billInterpreter.isInterpreted(b.getId())
					|| b.getSessionCode().equals(sessionCode)));
			return this;
		}
		
	}

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
		return processDatasets(BillGenerationCriteria.defaultCriteria(), data.getBuildDatasets(), new BuildReport(), false);
	}

	public List<InterpretationRequest> process(List<PoliscoreDatasetIF> buildDatasets, BuildReport report, boolean enableWebSearch, boolean isSecondFetch) throws IOException {
		val criteria = BillGenerationCriteria.defaultCriteria();
		criteria.enableWebSearch = enableWebSearch;
		return processDatasets(criteria, buildDatasets, report, isSecondFetch);
	}
	

	/**
	 * Processes all bills in a dataset
	 * 
	 * @param buildDatasets
	 * @param report
	 * @param enableWebSearch
	 * @param isSecondFetch
	 * @return
	 * @throws IOException
	 */
	public List<InterpretationRequest> processDatasets(BillGenerationCriteria criteria, List<PoliscoreDatasetIF> buildDatasets, BuildReport report, boolean isSecondFetch) throws IOException {
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
			processDataset(criteria, dataset, isSecondFetch);
		}

		Log.info("Bill interpretation request generation complete. Generated " + totalRequests + " requests.");

		return new ArrayList<>(requests);
	}
	
	/**
	 * Processes a list of bills.
	 * 
	 * @param billId
	 * @param report
	 * @param enableWebSearch
	 * @param isSecondFetch
	 * @return
	 * @throws IOException
	 */
	public List<InterpretationRequest> processBills(BillGenerationCriteria criteria, List<Bill> bills, BuildReport report, boolean isSecondFetch) throws IOException {
		totalRequests = 0;
		requests.clear();
		this.report = report;

		processBills(criteria, bills, isSecondFetch);
		
		return new ArrayList<>(requests);
	}

	private void processBills(BillGenerationCriteria criteria, List<Bill> bills, boolean isSecondFetch) {
		for(Bill bill : bills) {
			if (!billService.hasBillText(bill)) {
				Log.info("Skipping refresh request generation for " + bill.getId() + " because bill text is unavailable.");
				continue;
			}
	
			val latestBillText = billService.getBillText(bill)
					.orElseThrow(() -> new IllegalStateException("Latest bill text not found for " + bill.getId()));
	
			processBill(criteria, bill, latestBillText, !isSecondFetch, true);
		}
	}

	private void processDataset(BillGenerationCriteria criteria, PoliscoreDatasetIF dataset, boolean isSecondFetch) throws IOException {
		if (criteria.MAX_BILL_PROCESS != -1 && isSecondFetch)
			return;

		boolean includePressDirtyBills = !isSecondFetch;
		
		dataset.optimizeExists(s3, BillInterpretation.class);
		dataset.optimizeExists(s3, BillText.class);

		var requestBills = criteria.refine(dataset.query(Bill.class).stream(), this, includePressDirtyBills)
				.sorted(Comparator.comparing(Bill::getIntroducedDate).reversed()).toList();

		long total = requestBills.size();

		if (criteria.MAX_BILL_PROCESS != -1) {
			requestBills = requestBills.stream().limit(criteria.MAX_BILL_PROCESS).toList();
		}

		String outOf = (criteria.MAX_BILL_PROCESS != -1 && total > criteria.MAX_BILL_PROCESS) ? " out of " + total : "";
		Log.info("Processing " + requestBills.size() + " bills" + outOf + " for request generation on dataset "
				+ dataset.getDescription());

		processBills(criteria, requestBills, isSecondFetch);
	}

	private void processBill(BillGenerationCriteria criteria, Bill bill, BillText latestBillText, boolean includePressDirtyBills, boolean requireFreshOutputs) {
		// The press interpreter may have said this bill was dirty, but after the press
		// interps came back,
		// they came back as NO_INTERP. At this point, it's not actually dirty and
		// doesn't need to be interpreted.
		if (!requireFreshOutputs && criteria.CHECK_S3_EXISTS && billInterpreter.isInterpreted(bill.getId()) && includePressDirtyBills
				&& pressBillInterpGenerator.getDirtyBills().contains(bill)) {
			val interp = billService.getInterpretation(bill)
					.orElseThrow();

			var s3PressInterps = billService.getPressInterps(interp.getBillId());

			if (!s3PressInterps.stream().anyMatch(s3pi -> !interp.getPressInterps().contains(s3pi)))
				return;
		}

		bill.setText(latestBillText);

		String sBillText = latestBillText.getDocument();
		if (StringUtils.isBlank(sBillText)) {
			throw new UnsupportedOperationException("Bill text is empty for " + bill.getId());
		}

		String systemMsg = BillPrompt.getPromptForBill(false, criteria.enableWebSearch);
		val userMsg = billInterpreter.getUserMsgForBill(bill, sBillText, criteria.billProcessModel);

		if (tokenEstimatorService.estimateTokenCount(systemMsg + "\n" + userMsg) > criteria.billProcessModel.getContextWindowTokens()) {

			List<BillSlice> slices = billSlicer.slice(bill, bill.getText(), criteria.billProcessModel);

			if (slices.isEmpty())
				throw new UnsupportedOperationException("Slicer returned zero slices?");

			// The XMLBillSlicer returns the document text without the xml nodes, which usually results in bill text about half the size. For this reason, it's possible that the splitter could return a slice of size 1.
			if (slices.size() == 1) {
				if (!StringUtils.isBlank(slices.get(0).getText()))
					sBillText = slices.get(0).getText();

				val existingAggregate = billService.getInterpretation(bill)
						.orElse(null);
				if (shouldCreateFreshRequest(existingAggregate, latestBillText)) {
					createRequest(criteria, BillInterpretation.generateId(bill.getId(), latestBillText.getVersion(), null), bill, null,
							systemMsg,
							billInterpreter.getUserMsgForBill(bill, sBillText, criteria.billProcessModel),
							sBillText);
				}
			} else {
				val sliceInterps = new ArrayList<BillInterpretation>();

				for (int i = 0; i < slices.size(); ++i) {
					BillSlice slice = slices.get(i);
					val existingSlice = billService.getInterpretation(bill, slice.getSliceIndex())
							.orElse(null);

					if (shouldCreateFreshRequest(existingSlice, latestBillText)) {
						val oid = BillInterpretation.generateId(bill.getId(), latestBillText.getVersion(), slice.getSliceIndex());
						createRequest(criteria, oid, bill, slice.getSliceIndex(), BillPrompt.slicePrompt,
								slice.getText(), slice.getText());
					} else if (existingSlice != null) {
						sliceInterps.add(existingSlice);
					}
				}

				if (sliceInterps.size() == slices.size()) {
					systemMsg = BillPrompt.getPromptForBill(true, criteria.enableWebSearch);
					List<String> summaries = new ArrayList<>();

					for (int i = 0; i < slices.size(); ++i) {
						summaries.add(sliceInterps.get(i).getLongExplain());
					}

					val oid = BillInterpretation.generateId(bill.getId(), latestBillText.getVersion(), null);
					val existingAggregate = billService.getInterpretation(bill).orElse(null);
					if (!shouldCreateFreshRequest(existingAggregate, latestBillText)) {
						return;
					}

					if (tokenEstimatorService.estimateTokenCount(systemMsg, billInterpreter.getUserMsgForBill(bill, String.join("\n", summaries), criteria.billProcessModel)) > criteria.billProcessModel.getContextWindowTokens()) {
						summaries = new ArrayList<>();
						for (int i = 0; i < slices.size(); ++i) {
							val split = sliceInterps.get(i).getLongExplain().split("\n");
							for (int j = 0; j < Math.min(3, split.length); ++j) {
								summaries.add(split[j]);
							}
						}
					}

					String sliceTexts = String.join("\n", summaries);
					createRequest(criteria, oid, bill, null, systemMsg,
							billInterpreter.getUserMsgForBill(bill, sliceTexts, criteria.billProcessModel),
							sliceTexts);
				}
			}
		} else {
			val existingAggregate = billService.getInterpretation(bill)
					.orElse(null);
			if (shouldCreateFreshRequest(existingAggregate, latestBillText)) {
				createRequest(criteria, BillInterpretation.generateId(bill.getId(), latestBillText.getVersion(), null), bill, null, systemMsg, userMsg, sBillText);
			}
		}
	}

	private boolean shouldCreateFreshRequest(BillInterpretation existing, BillText latestBillText) {
		if (existing == null) {
			return true;
		}

		return !matchesBillText(existing, latestBillText);
	}

	private boolean matchesBillText(BillInterpretation existing, BillText latestBillText) {
		if (latestBillText == null || existing == null) {
			return false;
		}

		return StringUtils.equalsIgnoreCase(
				StringUtils.defaultString(latestBillText.getVersion()),
				StringUtils.defaultString(existing.getSourceBillTextVersion()));
	}

	private Stream<Bill> andNotInList(Stream<Bill> stream, List<String> billExcludeList) {
		return stream.filter(b -> !billExcludeList.contains(b.getId()));
	}
	
	private Stream<Bill> andNotAlreadyInterpreted(BillGenerationCriteria criteria, Stream<Bill> stream, boolean includePressDirtyBills) {
		return stream.filter(b -> (!criteria.CHECK_S3_EXISTS || getExistingInterpretation(b) == null
				|| (includePressDirtyBills && pressBillInterpGenerator.getDirtyBills().contains(b))));
	}
	
	private Stream<Bill> andNotAlreadyInterpretedOrInvalid(BillGenerationCriteria criteria, Stream<Bill> stream, boolean includePressDirtyBills) {
		return stream.filter(b -> (!criteria.CHECK_S3_EXISTS || !interpretedAndValid(criteria, b)
				|| (includePressDirtyBills && pressBillInterpGenerator.getDirtyBills().contains(b))));
	}

	private boolean interpretedAndValid(BillGenerationCriteria criteria, Bill b) {
		var interp = getExistingInterpretation(b);

		if (criteria.REPROCESS_INVALID_BILLS)
			return interp != null && interp.isValid();
		return interp != null;
	}

	private BillInterpretation getExistingInterpretation(Bill bill) {
		return billService.getInterpretation(bill).orElse(null);
	}

	private BillInterpretation requireInterpretation(Bill bill) {
		val interp = getExistingInterpretation(bill);
		if (interp == null) {
			throw new IllegalStateException("Expected bill interpretation for " + bill.getId());
		}
		return interp;
	}

	private void createRequest(BillGenerationCriteria criteria, String oid, Bill bill, Integer sliceIndex, String systemMsg, String userMsg, String billText) {
		if (StringUtils.isBlank(billText)) throw new UnsupportedOperationException("Bill text is empty for " + bill.getId() + (sliceIndex != null ? " slice " + sliceIndex : ""));
		
		// Since checking this is now expensive, we will comment it out. We already do these checks before we invoke createRequest so this is redundant anyway.
//		if (billProcessModel.estimateTokenCount(systemMsg + "\n" + userMsg) > billProcessModel.getContextWindowTokens()) {
//			throw new RuntimeException("Max user message length exceeded on " + oid);
//		}

		val req = InterpretationRequest.builder().data(new CustomData(oid)).systemMsg(systemMsg).userMsg(userMsg)
				.requestedModel(criteria.billProcessModel).build();
		
		new BillInterpretationRouter().route(req, criteria.billProcessModel, OpenAIModel.DEFAULT_MODEL_MINI, bill, billText);

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
