package us.poliscore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.ai.BatchOpenAIRequest;
import us.poliscore.ai.BatchOpenAIRequest.BatchBillMessage;
import us.poliscore.ai.BatchOpenAIRequest.BatchOpenAIBody;
import us.poliscore.ai.BatchOpenAIRequest.CustomOriginData;
import us.poliscore.ai.BatchOpenAIResponse;
import us.poliscore.entrypoint.batch.BatchOpenAIResponseImporter;
import us.poliscore.entrypoint.batch.PressBillInterpretationRequestGenerator;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.MemoryObjectService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.PressInterpService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name = "PressInterpModelComparator")
public class PressInterpModelComparator implements QuarkusApplication {

	private static final Logger logger = LoggerFactory.getLogger(PressInterpModelComparator.class);
	
    private static final String GPT4O = "gpt-4o";
    private static final String GPT4O_MINI = "gpt-4o-mini";
    private static final int SAMPLE_SIZE = 100;
    
    // If specified, we won't generate and send a new request to AI, we will simply reprocess an existing file.
//    private static final String REPROCESS_RESPONSE = null;
    private static final String REPROCESS_RESPONSE = "/Users/rrowlands/dev/projects/poliscore/databuilder/target/file-WSQi6X89qWPpudadPJnera.jsonl";

    @Inject LocalCachedS3Service s3;
    @Inject BillService billService;
    @Inject LegislatorService legService;
    @Inject OpenAIService aiService;

    @Inject PressInterpService pressService;
    @Inject BatchOpenAIResponseImporter responseImporter;
    @Inject private GovernmentDataService data;

    private final List<BatchOpenAIRequest> requests = new ArrayList<>();
    
    protected int total = 0;
    
    protected int changed = 0;

    @SneakyThrows
    protected void process() {
    	data.importDatasets();
        
        List<File> responses;
        
        if (StringUtils.isEmpty(REPROCESS_RESPONSE)) {
	        Log.info("Fetching press interpretation data from S3. This might take a minute...");
	
	        val all = s3.query(PressInterpretation.class).stream()
	                .filter(p -> GPT4O.equals(p.getMetadata().getModel()))
	                .collect(Collectors.toList());
	
	        Collections.shuffle(all, new Random(42));
	        List<PressInterpretation> sample = all.stream().limit(SAMPLE_SIZE).toList();
	        
	        for (PressInterpretation original : sample) {
	            Bill bill = data.getDataset().get(original.getBillId(), Bill.class).orElse(null);
	            if (bill == null) continue;
	
	            val origin = original.getOrigin();
	            val article = pressService.fetchArticleText(origin);
	            if (article == null || article.isBlank()) continue;
	
	            createRequest(bill, origin, article);
	        }
	
	        if (requests.isEmpty()) {
	            logger.info("No requests generated.");
	            return;
	        }
	
	        // Write and submit the batch
	        File reqFile = writeRequests();
	        responses = aiService.processBatch(List.of(reqFile));
        } else {
        	responses = Arrays.asList(new File[] { new File(REPROCESS_RESPONSE) });
        }

        // Import the results
        for (File f : responses) {
            compareNoInterpFlagsFromResponseFile(f);
        }

        logger.info("Program complete. Compared: " + total + " interpretations");
    }

    private void createRequest(Bill bill, InterpretationOrigin origin, String article) {
        val oid = PressInterpretation.generateId(bill.getId(), origin);
        val data = new CustomOriginData(origin, oid);

        val prompt = PressBillInterpretationRequestGenerator.PRESS_INTERPRETATION_PROMPT.replace("{{billIdentifier}}", buildBillIdentifier(bill));

        String text = "title: " + origin.getTitle() + "\nurl: " + origin.getUrl() + "\n\n" + article;
        int maxLen = OpenAIService.MAX_GPT4o_REQUEST_LENGTH - prompt.length();
        if (text.length() > maxLen) text = text.substring(0, maxLen);

        List<BatchBillMessage> messages = List.of(
                new BatchBillMessage("system", prompt),
                new BatchBillMessage("user", text)
        );

        requests.add(new BatchOpenAIRequest(data, new BatchOpenAIBody(messages, GPT4O_MINI)));
    }
    
    protected String buildBillIdentifier(Bill bill) {
    	String id = "United States, ";
    	
    	if (bill.getNamespace().equals(LegislativeNamespace.US_CONGRESS)) {
    		id += bill.getSessionCode() + "th Congress";
    	} else {
    		id += "State of " + bill.getNamespace().getDescription();
    	}
    	
    	return id + ", " +
                bill.getOriginatingChamber().getName(bill.getNamespace()) + "\n" +
                bill.getType() + " " + bill.getNumber() + " - " + bill.getName() +
                "\nIntroduced in " + bill.getIntroducedDate();
    }

    private File writeRequests() throws Exception {
        File f = new File("target/openapi-press-compare-mini.jsonl");
        val mapper = us.poliscore.PoliscoreUtil.getObjectMapper();
        val lines = requests.stream().map(req -> {
            try {
                return mapper.writeValueAsString(req);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();

        org.apache.commons.io.FileUtils.writeLines(f, "UTF-8", lines);
        logger.info("Wrote " + lines.size() + " requests to " + f.getAbsolutePath());
        return f;
    }
    
    private void compareNoInterpFlagsFromResponseFile(File responseFile) {
        val mapper = PoliscoreUtil.getObjectMapper();
        int localTotal = 0;
        int localChanged = 0;

        try (val reader = new BufferedReader(new FileReader(responseFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                val resp = mapper.readValue(line, BatchOpenAIResponse.class);

                if (resp.getError() != null || resp.getResponse().getStatus_code() >= 400) {
                    Log.warn("Skipping errored response: " + resp.getError());
                    continue;
                }

                val content = resp.getResponse().getBody().getChoices().get(0).getMessage().getContent();
                if (content == null) continue;

                val oid = resp.getCustomData().getOid();
                val origin = ((CustomOriginData) resp.getCustomData()).getOrigin();
                val billId = oid.split("-")[0].replace(PressInterpretation.ID_CLASS_PREFIX, Bill.ID_CLASS_PREFIX);
                var bill = data.getDataset().get(billId, Bill.class).get();

                val originalOpt = s3.get(PressInterpretation.generateId(billId, origin), PressInterpretation.class);
                if (originalOpt.isEmpty()) {
                    logger.error("Original interpretation not found for: " + oid);
                    continue;
                }

                boolean oldNoInterp = originalOpt.get().isNoInterp();
                boolean newNoInterp = content.trim().startsWith("NO_INTERPRETATION");

                localTotal++;
                total++;
                if (oldNoInterp != newNoInterp) {
                    localChanged++;
                    logger.info("NO_INTERP mismatch (" + buildBillIdentifier(bill).replaceAll("\n", " ") + "): " + origin.getUrl() + " | old=" + oldNoInterp + ", new=" + newNoInterp);
                }
            }

            logger.info("File complete: " + responseFile.getName() + " | Compared: " + localTotal + ", Changed: " + localChanged);

        } catch (Exception e) {
            throw new RuntimeException("Failed to process " + responseFile.getAbsolutePath(), e);
        }
    }

    @Override
    public int run(String... args) throws Exception {
		try {
	        process();
	        
	        Quarkus.waitForExit();
	        return 0;
		} catch (Throwable t) {
			t.printStackTrace();
			System.exit(1);
		}
		
		return 1;
    }

    public static void main(String[] args) {
        Quarkus.run(PressInterpModelComparator.class, args);
        Quarkus.asyncExit(0);
    }
}
