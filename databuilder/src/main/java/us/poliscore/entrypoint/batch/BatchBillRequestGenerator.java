package us.poliscore.entrypoint.batch;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreUtil;
import us.poliscore.ai.BatchOpenAIRequest;
import us.poliscore.ai.BatchOpenAIRequest.BatchBillMessage;
import us.poliscore.ai.BatchOpenAIRequest.BatchOpenAIBody;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.parsing.BillSlicer;
import us.poliscore.service.BillInterpretationService;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name="BatchBillRequestGenerator")
public class BatchBillRequestGenerator implements QuarkusApplication
{
	public static final long TOKEN_BLOCK_SIZE = 30000000;
	
	public static final List<String> specificFetch = null;
//	public static final List<String> specificFetch = Arrays.asList(Bill.generateId(LegislativeNamespace.US_COLORADO, "2173", "sb", 114));
	
	public static final boolean CHECK_S3_EXISTS = specificFetch == null;
	
	public static final OpenAIModel billProcessModel = OpenAIService.DEFAULT_MODEL;
	
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
	
	private long tokenLen = 0;
	
	private long totalRequests = 0;
	
	private List<BatchOpenAIRequest> requests = new ArrayList<BatchOpenAIRequest>();
	
	private List<File> writtenFiles = new ArrayList<File>();
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	public static void main(String[] args) {
		Quarkus.run(BatchBillRequestGenerator.class, args);
	}
	
	public List<File> process() throws IOException {
		return process(data.getBuildDatasets(), true, true);
	}
	
	public List<File> process(List<PoliscoreDataset> buildDatasets, boolean enableWebSearch, boolean isSecondFetch) throws IOException
	{
		tokenLen = 0;
		totalRequests = 0;
		requests = new ArrayList<BatchOpenAIRequest>();
		writtenFiles = new ArrayList<File>();
		
		Log.info("Generating batch request to interpret bills");
		
		data.importAllDatasets();
		
		int block = 1;
		
		for(val dataset : buildDatasets) {
			s3.optimizeExists(BillInterpretation.class, dataset.getSession().getKey());
			s3.optimizeExists(BillText.class, dataset.getSession().getKey());
		}
		
		for(val dataset : buildDatasets)
			processDataset(dataset, enableWebSearch, isSecondFetch, block);
		
		writeBlock(block++);
		
		Log.info("Batch bill request generator complete. Generated " + totalRequests + " requests.");
		
		return writtenFiles;
	}

	private void processDataset(PoliscoreDataset dataset, boolean enableWebSearch, boolean isSecondFetch, int block) throws IOException {
		if (specificFetch != null && isSecondFetch) return;
		
		boolean includePressDirtyBills = !isSecondFetch;
		
		val requestBills = dataset.query(Bill.class).stream()
				.filter(b -> specificFetch == null || specificFetch.contains(b.getId()))
				.filter(b -> (!CHECK_S3_EXISTS || !billInterpreter.isInterpreted(b.getId()) || (includePressDirtyBills && pressBillInterpGenerator.getDirtyBills().contains(b))))
//				.filter(b -> billInterpreter.isInterpreted(b.getId()) && s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).get().getRating() < 0 && b.getStatus().getProgress() == 1.0f)
				.filter(b -> s3.exists(BillText.generateId(b.getId()), BillText.class))
				.sorted(Comparator.comparing(Bill::getIntroducedDate).reversed())
				.toList();
		
		Log.info("Processing " + requestBills.size() + " bills for request generation.");
		
		for (Bill b : requestBills) {
			
			// The press interpreter may have said this bill was dirty, but after the press interps came back, they came back as NO_INTERP. At this point, it's not actually dirty and doesn't need to be interpreted.
			if (CHECK_S3_EXISTS && billInterpreter.isInterpreted(b.getId()) && includePressDirtyBills && pressBillInterpGenerator.getDirtyBills().contains(b)) {
				val interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).orElseThrow();
				
				var s3PressInterps = billService.getPressInterps(interp.getBillId());
				
				if (!s3PressInterps.stream().anyMatch(s3pi -> !interp.getPressInterps().contains(s3pi))) continue;
			}
			
			val billText = billService.getBillText(b).orElse(null);
			b.setText(billText);
			
			val userMsg = billInterpreter.getUserMsgForBill(b, b.getText().getDocument(), billProcessModel);
			
			if (userMsg.length() >= billProcessModel.getContextWindowStringLength())
	    	{
	    		List<BillSlice> slices = BillSlicer.factory(b.getText()).slice(b, b.getText(), billProcessModel.getContextWindowStringLength() - (userMsg.length() - b.getText().getDocument().length()));
	    		
	    		if (slices.size() == 0) throw new UnsupportedOperationException("Slicer returned zero slices?");
	    		else if (slices.size() == 1) {
	    			if (!StringUtils.isBlank(b.getText().getXml()))
	    				b.getText().setXml(slices.get(0).getText());
	    			
	    			List<BatchBillMessage> messages = new ArrayList<BatchBillMessage>();
					messages.add(new BatchBillMessage("system", billInterpreter.getPromptForBill(b, false, enableWebSearch)));
	    			messages.add(new BatchBillMessage("user", billInterpreter.getUserMsgForBill(b, b.getText().getDocument(), billProcessModel)));
	    			
	    			requests.add(new BatchOpenAIRequest(
	    					new CustomData(BillInterpretation.generateId(b.getId(), null)),
	    					new BatchOpenAIBody(messages)
	    			));
	    		} else {
	    			val sliceInterps = new ArrayList<BillInterpretation>();
	    			
	        		for (int i = 0; i < slices.size(); ++i)
	        		{
	        			BillSlice slice = slices.get(i);
	        			
	        			Optional<BillInterpretation> sliceInterp = s3.get(BillInterpretation.generateId(b.getId(), i), BillInterpretation.class);
	        			
	        			if (sliceInterp.isEmpty()) {
	        				val oid = BillInterpretation.generateId(b.getId(), slice.getSliceIndex());
	        				
	        				if (CHECK_S3_EXISTS && s3.exists(oid, BillInterpretation.class)) { continue; }
	        				
		        			createRequest(oid, BillInterpretationService.slicePrompt, slice.getText());
	        			} else {
	        				sliceInterps.add(sliceInterp.get());
	        			}
	        		}
	        		
	        		if (sliceInterps.size() == slices.size()) {
	        			List<String> summaries = new ArrayList<String>();
	            		
	            		for (int i = 0; i < slices.size(); ++i)
	            		{
	            			summaries.add(sliceInterps.get(i).getLongExplain());
	            		}
	            		
	            		val oid = BillInterpretation.generateId(b.getId(), null);
	            		
	            		if (CHECK_S3_EXISTS && billInterpreter.isInterpreted(oid)) { continue; }
	            		
	            		if (String.join("\n", summaries).length() > billProcessModel.getContextWindowStringLength()) {
	            			summaries = new ArrayList<String>();
	            			for (int i = 0; i < slices.size(); ++i)
		            		{
	            				val split = sliceInterps.get(i).getLongExplain().split("\n");
	            				
	            				for (int j = 0; j < Math.min(3, split.length); ++j) {
	            					summaries.add(split[j]);
	            				}
		            		}
	            		}
	            		
		    			createRequest(oid, billInterpreter.getPromptForBill(b, true, enableWebSearch), billInterpreter.getUserMsgForBill(b, String.join("\n", summaries), billProcessModel));
	        		}
	    		}
	    	}
			else {
    			createRequest(BillInterpretation.generateId(b.getId(), null), billInterpreter.getPromptForBill(b, false, enableWebSearch), userMsg);
			}
			
			if (tokenLen >= TOKEN_BLOCK_SIZE) {
				writeBlock(block++);
			}
		};
		
//		if (totalRequests == 0) {
//			val mostRecent = data.getDataset().query(Bill.class).stream()
//					.sorted(Comparator.comparing(Bill::getIntroducedDate).reversed())
//					.limit(100)
//					.filter(b -> (!CHECK_S3_EXISTS || !billInterpreter.isInterpreted(b.getId())))
//					.limit(10)
//					.map(b -> Arrays.asList(b.getId(), s3.exists(BillText.generateId(b.getId()), BillText.class)))
//					.toList();
//			
//			Log.info(mostRecent);
//		}
	}
	
	private void createRequest(String oid, String sysMsg, String userMsg) {
		if (userMsg.length() >= billProcessModel.getContextWindowStringLength()) {
			throw new RuntimeException("Max user message length exceeded on " + oid + " (" + userMsg.length() + " > " + billProcessModel.getContextWindowStringLength());
		}
		
		List<BatchBillMessage> messages = new ArrayList<BatchBillMessage>();
		messages.add(new BatchBillMessage("system", sysMsg));
		messages.add(new BatchBillMessage("user", userMsg));
		
		requests.add(new BatchOpenAIRequest(
				new CustomData(oid),
				new BatchOpenAIBody(messages)
		));
		
		tokenLen += (userMsg.length() / 4);
	}

	private void writeBlock(int block) throws IOException {
		if (requests.size() == 0) return;
		
		File f = requestFile(block);
		
		val mapper = PoliscoreUtil.getObjectMapper();
		val s = requests.stream().map(r -> {
			try {
				return mapper.writeValueAsString(r);
			} catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			}
		}).toList();
		
		FileUtils.write(f, String.join("\n", s), "UTF-8");
		
		totalRequests += requests.size();
		
		Log.info("Successfully wrote " + requests.size() + " requests to " + f.getAbsolutePath());
		
		writtenFiles.add(f);
		
		requests = new ArrayList<BatchOpenAIRequest>();
		tokenLen = 0;
	}
	
	public static File requestFile(int blockNum) {
		return new File(Environment.getDeployedPath(), "openapi-bills-bulk-" + blockNum + ".jsonl");
	}
	
	@Override
    public int run(String... args) throws Exception {
        process();
        
        Quarkus.waitForExit();
        return 0;
    }
}
