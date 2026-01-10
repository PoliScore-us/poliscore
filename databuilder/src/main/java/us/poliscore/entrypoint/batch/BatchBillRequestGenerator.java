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
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.parsing.BillSlicer;
import us.poliscore.service.BillInterpretationService;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name="BatchBillRequestGenerator")
public class BatchBillRequestGenerator implements QuarkusApplication
{
  public static final boolean REPROCESS_INVALID_BILLS = false;

  public static final int MAX_BILL_PROCESS = 1000; // -1 for infinite

  public static final List<String> specificFetch = null;
//	public static final List<String> specificFetch = Arrays.asList(
//	Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "hr", 21),
//	Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", "s", 6)
//);
//public static final List<String> specificFetch = Arrays.asList(Bill.generateId(LegislativeNamespace.US_COLORADO, "2173", "sb", 317));

//public static final List<String> specificFetch = Arrays.asList(
//  "BIL/us/co/2173/hb/1054","BIL/us/co/2173/hb/1062","BIL/us/co/2173/sb/303",
//  "BIL/us/co/2173/hb/1272","BIL/us/co/2173/hb/1069","BIL/us/co/2173/sb/132",
//  "BIL/us/co/2173/sb/201","BIL/us/co/2173/hb/1312","BIL/us/co/2173/hb/1208",
//  "BIL/us/co/2173/sb/11","BIL/us/co/2173/sb/77","BIL/us/co/2173/sb/160"
//);

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

  private long totalRequests = 0;

  private final List<InterpretationRequest> requests = new ArrayList<>();

  public static List<String> PROCESS_BILL_TYPE =
      Arrays.asList(CongressionalBillType.values()).stream()
        .filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt))
        .map(bt -> bt.getName().toLowerCase())
        .collect(Collectors.toList());

  public static void main(String[] args) {
    Quarkus.run(BatchBillRequestGenerator.class, args);
  }

  public List<InterpretationRequest> process() throws IOException {
    data.importAllDatasets();
    return process(data.getBuildDatasets(), true, false);
  }

  public List<InterpretationRequest> process(List<PoliscoreDatasetIF> buildDatasets, boolean enableWebSearch, boolean isSecondFetch)
      throws IOException
  {
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
    if (MAX_BILL_PROCESS != -1 && isSecondFetch) return;
    if (specificFetch != null && isSecondFetch) return;

    boolean includePressDirtyBills = !isSecondFetch;

    var stream = dataset.query(Bill.class).stream()
        .filter(b -> specificFetch == null || specificFetch.contains(b.getId()))
        .filter(b -> s3.exists(BillText.generateId(b.getId()), BillText.class));

    if (specificFetch == null) {
      stream = andNotAlreadyInterpretedOrInvalid(stream, includePressDirtyBills);

      // stream = ifInterpretedThenInSession(stream, "119");
      // stream = ifInterpretedThenByModel(stream, "gpt-4.1");
      // stream = ifInterpretedThenAboveStatusProgress(stream, 0.1f);
      // stream = ifInterpretedThenBeforeDate(stream, LocalDate.of(2025, 12, 11));
    }

    var requestBills = stream
        .sorted(Comparator.comparing(Bill::getIntroducedDate).reversed())
        .toList();

    long total = requestBills.size();

    if (MAX_BILL_PROCESS != -1) {
      requestBills = requestBills.stream().limit(MAX_BILL_PROCESS).toList();
    }

    String outOf = (MAX_BILL_PROCESS != -1 && total > MAX_BILL_PROCESS) ? " out of " + total : "";
    Log.info("Processing " + requestBills.size() + " bills" + outOf + " for request generation on dataset " + dataset.getDescription());

    for (Bill b : requestBills) {

      // The press interpreter may have said this bill was dirty, but after the press interps came back,
      // they came back as NO_INTERP. At this point, it's not actually dirty and doesn't need to be interpreted.
      if (CHECK_S3_EXISTS && billInterpreter.isInterpreted(b.getId()) && includePressDirtyBills && pressBillInterpGenerator.getDirtyBills().contains(b)) {
        val interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).orElseThrow();

        var s3PressInterps = billService.getPressInterps(interp.getBillId());

        if (!s3PressInterps.stream().anyMatch(s3pi -> !interp.getPressInterps().contains(s3pi))) continue;
      }

      val billText = billService.getBillText(b).orElse(null);
      b.setText(billText);

      val userMsg = billInterpreter.getUserMsgForBill(b, b.getText().getDocument(), billProcessModel);

      if (userMsg.length() >= billProcessModel.getContextWindowStringLength()) {

        List<BillSlice> slices =
            BillSlicer.factory(b.getText()).slice(
                b,
                b.getText(),
                billProcessModel.getContextWindowStringLength()
                    - (userMsg.length() - b.getText().getDocument().length())
            );

        if (slices.isEmpty()) throw new UnsupportedOperationException("Slicer returned zero slices?");

        if (slices.size() == 1) {
          if (!StringUtils.isBlank(b.getText().getXml()))
            b.getText().setXml(slices.get(0).getText());

          createRequest(
              BillInterpretation.generateId(b.getId(), null),
              b.getId(),
              null,
              billInterpreter.getPromptForBill(b, false, enableWebSearch),
              billInterpreter.getUserMsgForBill(b, b.getText().getDocument(), billProcessModel),
              effortForBill(b, b.getText().getXml())
          );
        } else {
          val sliceInterps = new ArrayList<BillInterpretation>();

          for (int i = 0; i < slices.size(); ++i) {
            BillSlice slice = slices.get(i);

            Optional<BillInterpretation> sliceInterp =
                s3.get(BillInterpretation.generateId(b.getId(), i), BillInterpretation.class);

            if (sliceInterp.isEmpty()) {
              val oid = BillInterpretation.generateId(b.getId(), slice.getSliceIndex());

              if (s3.exists(oid, BillInterpretation.class)) continue;

              createRequest(
                  oid,
                  b.getId(),
                  slice.getSliceIndex(),
                  BillInterpretationService.slicePrompt,
                  slice.getText(),
                  minEffort
              );
            } else {
              sliceInterps.add(sliceInterp.get());
            }
          }

          if (sliceInterps.size() == slices.size()) {
            List<String> summaries = new ArrayList<>();

            for (int i = 0; i < slices.size(); ++i) {
              summaries.add(sliceInterps.get(i).getLongExplain());
            }

            val oid = BillInterpretation.generateId(b.getId(), null);

            if (CHECK_S3_EXISTS && billInterpreter.isInterpreted(oid)) continue;

            if (String.join("\n", summaries).length() > billProcessModel.getContextWindowStringLength()) {
              summaries = new ArrayList<>();
              for (int i = 0; i < slices.size(); ++i) {
                val split = sliceInterps.get(i).getLongExplain().split("\n");
                for (int j = 0; j < Math.min(3, split.length); ++j) {
                  summaries.add(split[j]);
                }
              }
            }

            createRequest(
                oid,
                b.getId(),
                null,
                billInterpreter.getPromptForBill(b, true, enableWebSearch),
                billInterpreter.getUserMsgForBill(b, String.join("\n", summaries), billProcessModel),
                minEffort == ReasoningEffort.LOW ? ReasoningEffort.MEDIUM : minEffort
            );
          }
        }
      } else {
        createRequest(
            BillInterpretation.generateId(b.getId(), null),
            b.getId(),
            null,
            billInterpreter.getPromptForBill(b, false, enableWebSearch),
            userMsg,
            effortForBill(b, userMsg)
        );
      }
    }
  }
  
  private ReasoningEffort effortForBill(Bill b, String billText) {
	  if (minEffort != ReasoningEffort.LOW) return minEffort;
	  
	  if (b.getStatus().getProgress() > 0.0f || billText.length() > 10000)
		  return ReasoningEffort.MEDIUM;
	  else
		  return minEffort;
  }

  private Stream<Bill> andNotAlreadyInterpretedOrInvalid(Stream<Bill> stream, boolean includePressDirtyBills) {
    return stream.filter(b -> (!CHECK_S3_EXISTS
        || !interpretedAndValid(b)
        || (includePressDirtyBills && pressBillInterpGenerator.getDirtyBills().contains(b))
    ));
  }

  private boolean interpretedAndValid(Bill b) {
    var interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).orElse(null);

    if (REPROCESS_INVALID_BILLS) return interp != null && interp.isValid();
    return interp != null;
  }

  private Stream<Bill> ifInterpretedThenByModel(Stream<Bill> stream, String model) {
    return stream.filter(b ->
      !billInterpreter.isInterpreted(b.getId()) ||
        (s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).get()
          .getMetadata().getModel().toLowerCase().equals(model))
    );
  }

  private Stream<Bill> ifInterpretedThenAboveStatusProgress(Stream<Bill> stream, float progress) {
    return stream.filter(b -> !billInterpreter.isInterpreted(b.getId()) || b.getStatus().getProgress() > progress);
  }

  private Stream<Bill> ifInterpretedThenBeforeDate(Stream<Bill> stream, LocalDate beforeDate) {
    return stream.filter(b ->
      beforeDate.isAfter(s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).get().getDate())
    );
  }

  private Stream<Bill> ifInterpretedThenHasZeroSearchReferences(Stream<Bill> stream) {
    return stream.filter(b ->
      s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).get().getPressInterps().size() == 0
    );
  }

  private Stream<Bill> ifInterpretedThenInSession(Stream<Bill> stream, String sessionCode) {
    return stream.filter(b -> !billInterpreter.isInterpreted(b.getId()) || (b.getSessionCode().equals(sessionCode)));
  }

  private void createRequest(String oid, String billId, Integer sliceIndex, String systemMsg, String userMsg, ReasoningEffort effort) {
    if (userMsg.length() >= billProcessModel.getContextWindowStringLength()) {
      throw new RuntimeException("Max user message length exceeded on " + oid + " (" + userMsg.length()
          + " > " + billProcessModel.getContextWindowStringLength());
    }

    val req = InterpretationRequest.builder()
        .data(new CustomData(oid))
        .systemMsg(systemMsg)
        .userMsg(userMsg)
        .requestedModel(billProcessModel)
        .reasoningEffort(effort)
        .build();

    requests.add(req);
    totalRequests++;
  }

  @Override
  public int run(String... args) throws Exception {
    List<InterpretationRequest> reqs = process();
    Log.info("Generated " + reqs.size() + " BillInterpretationRequest objects.");
    Quarkus.waitForExit();
    return 0;
  }
}
