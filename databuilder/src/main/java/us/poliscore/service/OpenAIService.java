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

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.batches.Batch;
import com.openai.models.batches.Batch.Status;
import com.openai.models.batches.BatchCreateParams;
import com.openai.models.batches.BatchCreateParams.CompletionWindow;
import com.openai.models.batches.BatchCreateParams.Endpoint;
import com.openai.models.beta.realtime.ResponseCreateEvent;
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
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.AISliceInterpretationMetadata;
import us.poliscore.model.bill.BillSlice;

@ApplicationScoped
public class OpenAIService {
	
	public static final String PROVIDER = "openai";
	
	public static final String MODEL = "gpt-4.1";
	
	public static final int PROMPT_VERSION = 0;
	
	// If a batch is sent with a number of requests less than or equal to this number we will not use the batch api and process it immediately.
	// This is because the OpenAI Batch API has been known to take forever or even fail to process.
	public static final int IMMEDIATE_PROCESS_THRESHOLD = 5;
	
	public static int MAX_REQUEST_LENGTH = 3500000;
	public static int MAX_GPT4o_REQUEST_LENGTH = 490000; // GPT-4o context window in tokens is 128,000, which is 500k string length.
	
	public static final int MAX_OUTPUT_TOKENS = 300000;
	
	public static final int WAIT_BETWEEN_CALLS = 60; // in seconds
	
	@Inject
    protected SecretService secret;
	
	protected LocalDateTime nextCallTime = null;
	
	public static AIInterpretationMetadata metadata()
	{
		return AIInterpretationMetadata.construct(PROVIDER, MODEL, PROMPT_VERSION);
	}
	
	public static AIInterpretationMetadata metadata(BillSlice slice)
	{
		return AISliceInterpretationMetadata.construct(PROVIDER, MODEL, PROMPT_VERSION, slice);
	}
	
	public String chat(String systemMsg, String userMsg) { return this.chat(systemMsg, userMsg, null); }
	
	@SneakyThrows
	public String chat(String systemMsg, String userMsg, String model)
    {
		if (userMsg.length() > OpenAIService.MAX_REQUEST_LENGTH) {
			throw new IndexOutOfBoundsException();
		}
		if (StringUtils.isEmpty(systemMsg) || StringUtils.isEmpty(userMsg)) {
			throw new IllegalArgumentException();
		}
		
		if (nextCallTime != null && ChronoUnit.SECONDS.between(LocalDateTime.now(), nextCallTime) > 0)
		{
			Thread.sleep(ChronoUnit.SECONDS.between(LocalDateTime.now(), nextCallTime) * 1000);
		}
		
		OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(secret.getOpenAISecret()).build();
		
		val paramBuilder = ResponseCreateParams.builder()
				.instructions(systemMsg)
		        .input(userMsg)
		        .model(StringUtils.defaultIfEmpty(model, MODEL))
		        .maxOutputTokens(MAX_OUTPUT_TOKENS);
		
		if (!model.equals("o3-deep-research"))
			paramBuilder.temperature(0.0d); // We don't want randomness. Give us predictability and accuracy
		else
			paramBuilder.tools(List.of(
			        Tool.ofWebSearch(WebSearchTool.builder().type(Type.WEB_SEARCH_PREVIEW).build())
			        ));
			
		val params = paramBuilder.build();
		
		System.out.println("Sending request to open ai with message size " + userMsg.length());
		Response response = client.responses().create(params);
    	
    	if (response.error().isPresent())
    	{
    		throw new RuntimeException("OpenAI encountered an error while processing request. " + response.error().get().message());
    	}
    	
    	if (!response.status().get().equals(ResponseStatus.COMPLETED)) {
    		throw new RuntimeException("OpenAI's response status was not equal to completed. " + response.status().get());
    	}
    	
    	nextCallTime = LocalDateTime.now().plusSeconds(Math.round(((double)userMsg.length() / (double)OpenAIService.MAX_REQUEST_LENGTH) * (double)WAIT_BETWEEN_CALLS)).plusSeconds(2);
    	
    	return response.output().stream()
    			.filter(r -> r.message().isPresent())
    			.map(r -> r.message().get().content().stream()
    					.filter(c -> c.outputText().isPresent())
    					.map(c -> c.outputText().get().text())
    					.reduce("",(a,b) -> a + b))
    			.reduce("", (a,b) -> a + b);
    }
	
	/**
	 * Submits a batch of files, awaits their processing, and then downloads the results.
	 */
	@SneakyThrows
	public List<File> processBatch(List<File> files) {
		if (files.size() == 1 && Files.lines(files.get(0).toPath()).count() <= IMMEDIATE_PROCESS_THRESHOLD) return processBatchImmediately(files);
		
		OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(secret.getOpenAISecret()).build();
		
		final List<Batch> batches = new ArrayList<Batch>();
		final List<File> responseFiles = new ArrayList<File>();
		
		for (File f : files) {
			Log.info("Sending request batch file to OpenAI [" + f.getAbsolutePath() + "]");
			
			String fileId = client.files().create(
				    FileCreateParams.builder()
				      .file(f.toPath())
				      .purpose(FilePurpose.BATCH)
				      .build()
				).id();
			
			Batch batch = client.batches().create(BatchCreateParams.builder()
				    .inputFileId(fileId)
				    .endpoint(Endpoint.V1_RESPONSES)
				    .completionWindow(CompletionWindow._24H)
				    .build());
			
			batches.add(batch);
		}
		
		Log.info("Awaiting OpenAI to process our batch files (this will take a while)...");
		
		while (batches.size() > 0) {
			Thread.sleep(Duration.ofMinutes(1));
			
			Iterator<Batch> it = batches.iterator();
			
			while (it.hasNext()) {
				val b = it.next();
				
				RetryPolicy<Object> retryPolicy = RetryPolicy.builder()
				    .handle(SocketTimeoutException.class)
				    .withBackoff(1, 8, ChronoUnit.SECONDS)
				    .withMaxRetries(3)
				    .onRetry(e -> Log.warn("Retrying due to timeout..."))
				    .onFailure(e -> Log.error("Retries exhausted", e.getException()))
				    .build();
				
				Batch b2 = Failsafe.with(retryPolicy).get(() -> client.batches().retrieve(b.id()));
				
				if (b2.status().equals(Status.COMPLETED) && b2.outputFileId().isPresent()) {
					val body = client.files().content(b2.outputFileId().get());
					
					val f = new File(Environment.getDeployedPath(), b2.outputFileId() + ".jsonl");
					Files.copy(body.body(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
					responseFiles.add(f);
					
					it.remove();
					
					Log.info("Batch file successfully processed by OpenAI [" + f.getAbsolutePath() + "].");
				}
			}
		}
		
		return responseFiles;
	}
	
	/**
	 * Processes a batch of jsonl files immediately without submitting to OpenAI's batch API.
	 * 
	 * For each file:
	 * - Reads each JSONL line containing a chat completion request format.
	 * - Extracts the system and user messages, as well as the target model (if specified).
	 * - Invokes the OpenAI chat completion API for each line individually.
	 * - Saves all responses to a corresponding output file in OpenAI batch response format.
	 * 
	 * Returns a list of the output files containing the responses.
	 *
	 * @param files list of jsonl files containing batch chat completion requests
	 * @return list of output files containing responses for each input file
	 */
	@SneakyThrows
	public List<File> processBatchImmediately(List<File> files) {
		List<File> responseFiles = new ArrayList<>();

		for (File jsonlFile : files) {
			Log.info("Processing immediate batch for file: " + jsonlFile.getAbsolutePath());

			List<String> lines = FileUtils.readLines(jsonlFile, "UTF-8");
			List<String> outputLines = new ArrayList<>();

			for (String line : lines) {
				if (StringUtils.isBlank(line)) {
					continue;
				}

				val objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
				val node = objectMapper.readTree(line);
				val body = node.get("body");

				if (body == null || !body.has("messages")) {
					throw new IllegalArgumentException("Missing 'body' or 'messages' in line: " + line);
				}

				String model = body.has("model") ? body.get("model").asText() : null;
				String systemMsg = null;
				String userMsg = null;

				for (val msgNode : body.get("messages")) {
					String role = msgNode.get("role").asText();
					String content = msgNode.get("content").asText();

					if ("system".equals(role)) {
						systemMsg = content;
					} else if ("user".equals(role)) {
						userMsg = content;
					}

					if (systemMsg != null && userMsg != null) {
						break;
					}
				}

				if (systemMsg == null || userMsg == null) {
					throw new IllegalArgumentException("Expected at least one system and one user message in: " + line);
				}

				val customId = node.path("custom_id").asText(null);

				String assistantResponse = chat(systemMsg, userMsg, model);

				// Build the "body" part (chat completion result)
				val responseNode = objectMapper.createObjectNode();
				responseNode.put("id", "chatcmpl-" + java.util.UUID.randomUUID());
				responseNode.put("object", "chat.completion");
				responseNode.put("created", System.currentTimeMillis() / 1000);
				responseNode.put("model", StringUtils.defaultIfEmpty(model, MODEL));

				val choicesArray = objectMapper.createArrayNode();
				val choice = objectMapper.createObjectNode();
				choice.put("index", 0);

				val message = objectMapper.createObjectNode();
				message.put("role", "assistant");
				message.put("content", assistantResponse);
				choice.set("message", message);
				choice.put("finish_reason", "stop");
				choicesArray.add(choice);
				responseNode.set("choices", choicesArray);

				val usageNode = objectMapper.createObjectNode();
				usageNode.put("prompt_tokens", 0);
				usageNode.put("completion_tokens", 0);
				usageNode.put("total_tokens", 0);
				responseNode.set("usage", usageNode);

				// Wrap response inside OpenAI batch envelope format
				val responseEnvelope = objectMapper.createObjectNode();
				responseEnvelope.put("status_code", 200);
				responseEnvelope.set("body", responseNode);

				val out = objectMapper.createObjectNode();
				if (customId != null) {
					out.put("custom_id", customId);
				}
				out.set("response", responseEnvelope);

				outputLines.add(out.toString());
			}

			File outputFile = new File(jsonlFile.getParentFile(), jsonlFile.getName() + ".out.jsonl");
			FileUtils.writeLines(outputFile, outputLines);

			Log.info("Wrote responses to file: " + outputFile.getAbsolutePath());
			responseFiles.add(outputFile);
		}

		return responseFiles;
	}



}
