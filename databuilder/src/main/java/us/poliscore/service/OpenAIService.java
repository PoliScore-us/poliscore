package us.poliscore.service;

import java.io.File;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.batches.Batch;
import com.openai.models.batches.Batch.Status;
import com.openai.models.batches.BatchCreateParams;
import com.openai.models.batches.BatchCreateParams.CompletionWindow;
import com.openai.models.batches.BatchCreateParams.Endpoint;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FilePurpose;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.Tool;
import com.openai.models.responses.WebSearchTool;
import com.openai.models.responses.WebSearchTool.Type;

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.ai.MinuteRateLimiter;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.bill.OpenAIBatchJsonlSerializer;
import us.poliscore.entrypoint.DatabaseBuilder;
import us.poliscore.entrypoint.batch.BatchOpenAIResponseImporter;
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.AISliceInterpretationMetadata;
import us.poliscore.model.BuildReport;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.service.openai.OpenAIFlexBatchProcessor;

@ApplicationScoped
public class OpenAIService {
	
	public static final Logger logger = LoggerFactory.getLogger(OpenAIService.class);
	
	public static final String PROVIDER = "openai";
	
	private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30); // single OpenAI request cap
	
	public static final int PROMPT_VERSION = 0;
	
	// If a batch is sent with a number of requests less than or equal to this number we will not use the batch api and process it immediately.
	// This is because the OpenAI Batch API has been known to take forever or even fail to process.
	public static final int IMMEDIATE_PROCESS_THRESHOLD = 5;
	
	@Inject
    protected SecretService secret;
	
	@Inject
	private BatchOpenAIResponseImporter responseImporter;
	
	@Inject OpenAIFlexBatchProcessor flexBatchProcessor;
	
	protected LocalDateTime nextCallTime = null;
	
	volatile OpenAIClient openAiClient;
	
	private final ConcurrentHashMap<String, MinuteRateLimiter> limiters = new ConcurrentHashMap<>();
	
	public record Usage(long promptTokens, long completionTokens) {
	  public long totalTokens() { return promptTokens + completionTokens; }
	}

	public record ChatResult(String content, Usage usage, double costUsd) {}
	
	public static AIInterpretationMetadata metadata()
	{
		return AIInterpretationMetadata.construct(PROVIDER, OpenAIModel.DEFAULT_MODEL.getId(), PROMPT_VERSION, DatabaseBuilder.AGENTIC_WEB_SEARCH);
	}
	
	public static AIInterpretationMetadata metadata(BillSlice slice)
	{
		return AISliceInterpretationMetadata.construct(PROVIDER, OpenAIModel.DEFAULT_MODEL.getId(), PROMPT_VERSION, DatabaseBuilder.AGENTIC_WEB_SEARCH, slice);
	}
	
	private MinuteRateLimiter limiterFor(OpenAIModel model) {
	  OpenAIModel.RateLimit rl = model.getRateLimit();
	  return limiters.computeIfAbsent(model.getId(),
	      id -> new MinuteRateLimiter(rl.getRpm(), rl.getTpm()));
	}
	
	private void waitForRateLimit(OpenAIModel model, int tokensToBeUsed) throws InterruptedException {
	  limiterFor(model).acquire(tokensToBeUsed);
	}
	
	public synchronized OpenAIClient getClient() {
	  if (openAiClient == null) {
		  openAiClient = OpenAIOkHttpClient.builder()
		      .apiKey(secret.getOpenAISecret())
		      .timeout(REQUEST_TIMEOUT)
		      .build();
	  }
	  return openAiClient;
	}

	
	@SneakyThrows
	public ChatResult chat(InterpretationRequest request)
    {
		val model = Objects.requireNonNullElse(request.getRequestedModel(), OpenAIModel.DEFAULT_MODEL);
		String systemMsg = request.getSystemMsg();
		String userMsg = request.getUserMsg();
		val effort = Objects.requireNonNullElse(request.getReasoningEffort(), ReasoningEffort.LOW);
		
		if (userMsg.length() > model.getContextWindowStringLength()) {
			throw new IndexOutOfBoundsException();
		}
		if (StringUtils.isEmpty(systemMsg) || StringUtils.isEmpty(userMsg)) {
			throw new IllegalArgumentException();
		}
		
		int estimatedTokens = userMsg.length() / 3; // tokens are about 4 per char... but we're being conservative here.
		waitForRateLimit(model, estimatedTokens);
		
		OpenAIModel _model = ObjectUtils.defaultIfNull(model, OpenAIModel.DEFAULT_MODEL);
		
		val paramBuilder = ResponseCreateParams.builder()
				.instructions(systemMsg)
		        .input(userMsg)
		        .model(_model.getId())
		        .maxOutputTokens(_model.getMaxOutputTokens());
		
		if (_model.isSupportsSearch())
			paramBuilder.tools(List.of(
			        Tool.ofWebSearch(WebSearchTool.builder().type(Type.WEB_SEARCH_PREVIEW).build())
			        ));
		
		paramBuilder.serviceTier(ResponseCreateParams.ServiceTier.FLEX);
		
		if (_model.isSupportsTemperature())
			paramBuilder.temperature(0.0d); // We don't want randomness. Give us predictability and accuracy
		
		if (_model.isSupportsReasoning())
			paramBuilder.reasoning(Reasoning.builder().effort(effort).build()); // TODO : In theory you're supposed to be able to get reasoning if you add ".summary(Reasoning.Summary.CONCISE)" (and your organization is verified), but I haven't been able to get it to work. The remote just doesn't include the reasoning in the response.
			
		val params = paramBuilder.build();
		
		Log.info("Intepreting " + request.getData().getOid() + " using model " + model.getId() + " with reasoning effort " + effort.asString() + " and message size " + userMsg.length());
		RetryPolicy<Response> retryPolicy = RetryPolicy.<Response>builder()
			    .handle(SocketTimeoutException.class, InternalServerException.class,
			    		OpenAIInvalidDataException.class, // Even though this runs counter to their documentation, this exception is actually thrown wrapping a "SocketException: connection reset", so we definitely want to retry it.
			    		BadRequestException.class // OpenAI threw this once saying our prompt was invalid. Seems to be something they do non-deterministically on rare occasion. Try again.
			    		)
			    .handleIf((failure) -> {
			        String msg = failure.getMessage();
			        return msg != null && msg.toLowerCase().contains("rate limit");
			    })
//			    .handleResultIf(r -> r == null || !r.status().isPresent() || r.status().get() != ResponseStatus.COMPLETED)
			    .withBackoff(2, 900, ChronoUnit.SECONDS)
			    .withJitter(0.25)
			    .withMaxRetries(30)
			    .onRetry(e -> Log.warn("Retrying due to " + (e.getLastException() == null ? "invalid response" : "retryable exception [" + e.getLastException().getMessage() + "]")))
			    .onFailure(e -> Log.error("Retries exhausted", e.getException()))
			    .build();
		
		Response response = Failsafe.with(retryPolicy).get(() -> getClient().responses().create(params));
    	
    	if (response.error().isPresent())
    	{
    		throw new RuntimeException("OpenAI encountered an error while processing request. " + response.error().get().message());
    	}
    	
    	if (!response.status().get().equals(ResponseStatus.COMPLETED)) {
    		if (response.status().get().equals(ResponseStatus.INCOMPLETE) && model.isSupportsReasoning() && ReasoningEffort.LOW.equals(effort)) {
    			request.setReasoningEffort(ReasoningEffort.MEDIUM);
    			logger.error("Low reasoning attempt caused a reponse status INCOMPLETE. Retrying request at MEDIUM reasoning level.");
    			return chat(request); // Retry the request with higher reasoning
    		} else
    			throw new RuntimeException("OpenAI's response status was not equal to completed. " + response.status().get());
    	}
    	
    	String responseBody = response.output().stream()
    			.filter(r -> r.message().isPresent())
    			.map(r -> r.message().get().content().stream()
    					.filter(c -> c.outputText().isPresent())
    					.map(c -> c.outputText().get().text())
    					.reduce("",(a,b) -> a + b))
    			.reduce("", (a,b) -> a + b);
    	
    	Usage usage = response.usage()
	      .map(u -> new Usage(u.inputTokens(), u.outputTokens())) // <-- rename to what your SDK exposes
	      .orElse(new Usage(0, 0));

    	double costUsd = _model.estimateCostUsd(usage);

    	return new ChatResult(responseBody, usage, costUsd);
    }
	
	/**
	 * Submits a batch of files, awaits their processing, and then downloads the results.
	 */
	@SneakyThrows
	public List<File> processBatch(BuildReport report, List<InterpretationRequest> requests) {

	    if (requests == null || requests.isEmpty()) {
	        return List.of();
	    }

	    // If small enough, don't use Batch API (too slow / flaky for tiny jobs)
	    if (requests.size() <= IMMEDIATE_PROCESS_THRESHOLD) {
	        return processBatchImmediately(report, requests);
	    }

	    OpenAIClient client = OpenAIOkHttpClient.builder()
	            .apiKey(secret.getOpenAISecret())
	            .build();

	    // 1) Serializer writes chunked batch input files (handles OpenAI file size limit)
	    var buildTemp = new File(System.getProperty("user.home") + "/appdata/poliscore/build");
	    buildTemp.mkdirs();

	    OpenAIBatchJsonlSerializer serializer = new OpenAIBatchJsonlSerializer();

	    List<File> inputFiles = serializer.writeChunkedJsonlFiles(requests, buildTemp, "openai-bills.in");

	    Log.info("Prepared " + inputFiles.size() + " OpenAI batch input file(s).");

	    // 2) Submit each file as a batch
	    final List<Batch> batches = new ArrayList<>();
	    final List<File> responseFiles = new ArrayList<>();

	    for (File f : inputFiles) {
	        Log.info("Sending request batch file to OpenAI [" + f.getAbsolutePath() + "]");

	        String fileId = client.files().create(
	                FileCreateParams.builder()
	                        .file(f.toPath())
	                        .purpose(FilePurpose.BATCH)
	                        .build()
	        ).id();

	        Batch batch = client.batches().create(
	                BatchCreateParams.builder()
	                        .inputFileId(fileId)
	                        .endpoint(Endpoint.V1_CHAT_COMPLETIONS)
	                        .completionWindow(CompletionWindow._24H)
	                        .build()
	        );

	        batches.add(batch);
	    }

	    // 3) Poll until all completed, download output files
	    Log.info("Awaiting OpenAI to process our batch files (polling every 60s)...");

	    while (!batches.isEmpty()) {
	        Thread.sleep(Duration.ofMinutes(1));

	        Iterator<Batch> it = batches.iterator();

	        while (it.hasNext()) {
	            val b = it.next();

	            RetryPolicy<Object> retryPolicy = RetryPolicy.builder()
	                    .handle(SocketTimeoutException.class, InternalServerException.class)
	                    .withBackoff(1, 8, ChronoUnit.SECONDS)
	                    .withMaxRetries(3)
	                    .onRetry(e -> Log.warn("Retrying due to timeout or retryable server exception..."))
	                    .onFailure(e -> Log.error("Retries exhausted", e.getException()))
	                    .build();

	            Batch latest = Failsafe.with(retryPolicy).get(() -> client.batches().retrieve(b.id()));

	            if (latest.status().equals(Status.COMPLETED) && latest.outputFileId().isPresent()) {
	                val outputId = latest.outputFileId().get();
	                val body = client.files().content(outputId);

	                val outFile = new File(Environment.getDeployedPath(), outputId + ".jsonl");
	                Files.copy(body.body(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
	                responseFiles.add(outFile);

	                it.remove();
	                Log.info("Batch file successfully processed by OpenAI [" + outFile.getAbsolutePath() + "].");
	            } else if (latest.status().equals(Status.FAILED)
	                    || latest.status().equals(Status.CANCELLED)
	                    || latest.status().equals(Status.EXPIRED)) {

	                String msg = "OpenAI batch ended in terminal status: " + latest.status() + " (batchId=" + latest.id() + ")";
	                Log.error(msg);
	                throw new RuntimeException(msg);
	            }
	        }
	    }

	    return responseFiles;
	}
	
	@SneakyThrows
	public List<File> processBatchImmediately(BuildReport report, List<InterpretationRequest> requests) {
		return flexBatchProcessor.processBatchImmediately(report, requests);
	}
	
//	/**
//	 * Processes a list of interpretation requests using OpenAI's service.
//	 */
//	@SneakyThrows
//	public List<File> processBatchImmediately(BuildReport report, List<InterpretationRequest> requests) {
//		Log.info("Performing " + requests.size() + " requests to OpenAI.");
//
//		var buildTemp = new File(System.getProperty("user.home") + "/appdata/poliscore/build");
//		buildTemp.mkdirs();
//		
//		File outputFile = new File(buildTemp, "openapi-bills.out.jsonl");
//		Log.info("Writing responses to file: " + outputFile.getAbsolutePath());
//		
//		int writtenRequests = 0;
//		
//		try (BufferedWriter writer = Files.newBufferedWriter(outputFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
//			for (InterpretationRequest request : requests) {
//				val objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
//
//				val model = Objects.requireNonNullElse(request.getRequestedModel(), OpenAIModel.DEFAULT_MODEL);
//				String assistantResponse = chat(request);
//
//				// Build the "body" part (chat completion result)
//				val responseNode = objectMapper.createObjectNode();
//				responseNode.put("id", "chatcmpl-" + java.util.UUID.randomUUID());
//				responseNode.put("object", "chat.completion");
//				responseNode.put("created", System.currentTimeMillis() / 1000);
//				responseNode.put("model", model.getId());
//
//				val choicesArray = objectMapper.createArrayNode();
//				val choice = objectMapper.createObjectNode();
//				choice.put("index", 0);
//
//				val message = objectMapper.createObjectNode();
//				message.put("role", "assistant");
//				message.put("content", assistantResponse);
//				choice.set("message", message);
//				choice.put("finish_reason", "stop");
//				choicesArray.add(choice);
//				responseNode.set("choices", choicesArray);
//
//				val usageNode = objectMapper.createObjectNode();
//				usageNode.put("prompt_tokens", 0);
//				usageNode.put("completion_tokens", 0);
//				usageNode.put("total_tokens", 0);
//				responseNode.set("usage", usageNode);
//
//				// Wrap response inside OpenAI batch envelope format
//				val responseEnvelope = objectMapper.createObjectNode();
//				responseEnvelope.put("status_code", 200);
//				responseEnvelope.set("body", responseNode);
//
//				val line = objectMapper.createObjectNode();
//				line.put("custom_id", BatchOpenAIRequest.customDataToCustomId(request.getData()) );
//				line.set("response", responseEnvelope);
//
//				writer.write(line.toString());
//                writer.newLine();
//                writer.flush();
//                
//                writtenRequests++;
//			}
//		} catch (Throwable t) {
//			Log.error("Fatal error encountered while processing immediate batch. We will hault bill processing, import what we have now, and continue.", t);
//			report.fatal(t);;
//			
//			// If we failed half-way through, and we've generated some requests, then we definitely want to import them
//			if (writtenRequests > 0)
//				responseImporter.process(report, outputFile);
//		}
//
//		return Arrays.asList(new File[] { outputFile });
//	}
}
