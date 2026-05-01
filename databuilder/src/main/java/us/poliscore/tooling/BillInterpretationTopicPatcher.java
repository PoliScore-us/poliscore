package us.poliscore.tooling;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreUtil;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.ai.BatchOpenAIResponse;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.BuildReport;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.TopicCanonicalizer;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name="BillInterpretationTopicPatcher")
public class BillInterpretationTopicPatcher implements QuarkusApplication {

	public static final long LIMIT = Long.MAX_VALUE;
	
	public static final File[] RESUME_FILES = null;
//	public static final File[] RESUME_FILES = new File[] {
//			new File("/Users/rrowlands/Downloads/batch_69f3ad70ffdc81908002820db497382e_output.jsonl")
//	};
	
	private static final OpenAIModel MODEL = OpenAIModel.GPT5nano;
	private static final int MAX_BILL_TEXT_CHARS = 120_000;
	private static final String REQUEST_PREFIX = "topicpatch/";
	private static final ObjectMapper MAPPER = PoliscoreUtil.getObjectMapper();

	private static final String SYSTEM_PROMPT = """
			You will be given official bill text. Generate only topics and alternate names for this bill.

			Other Names:
			Write alternate names this bill might actually be searched by. Include official short titles, popular/common names, widely used acronyms, and distinct shorthand references. Do not include the bill number, sponsor names, generic descriptions, the generated title, or speculative names. Use exact human-readable strings, remove duplicates, and output [] if there are no real alternate names.

			Topics:
			Write concise searchable topic phrases this bill substantively covers. Use lower-case noun phrases, prefer common public search language, and include both specific concepts and important plain-language synonyms when useful (for example "gun violence" and "firearm regulation"). Avoid overly broad umbrella topics unless the bill substantially targets them. Remove duplicates and output [] if no meaningful topic applies.

			Final output:
			Output exactly one JSON array of objects and no other text. The array must contain exactly one object using this shape:
			[{"topics":["topic phrase"],"otherNames":["alternate name"]}]

			Use only standard JSON double quotes, no markdown, no code fences, no comments, and no trailing commas.
			""";

	@Inject
	private GovernmentDataService data;

	@Inject
	private LocalCachedS3Service s3;

	@Inject
	private BillService billService;

	@Inject
	private OpenAIService openAI;

	protected void process() throws IOException {
		data.importAllDatasets();

		int totalRequests = 0;
		for (var dataset : data.getBuildDatasets()) {
			dataset.optimizeExists(s3, BillInterpretation.class);
			dataset.optimizeExists(s3, BillText.class);

			List<File> responseFiles;
			int expected = 0;
			if (RESUME_FILES == null) {
				List<InterpretationRequest> requests = new ArrayList<>();
				for (Bill bill : dataset.query(Bill.class)
						.stream().limit(LIMIT).toList()
						) {
					val interp = billService.getInterpretation(bill).orElse(null);
					if (interp == null || hasTopicFields(interp)) {
						continue;
					}
	
					val billText = billService.getBillText(bill).orElse(null);
					if (billText == null || StringUtils.isBlank(billText.getDocument())) {
						Log.warn("Skipping " + bill.getId() + " because it has no bill text.");
						continue;
					}
	
					requests.add(createRequest(bill, billText));
				}
	
				Log.info("Updating " + requests.size() + " bills in namespace " + dataset.getDescription());
				if (requests.isEmpty()) {
					Log.info("Namespace " + dataset.getDescription() + " is done.");
					continue;
				}
	
				expected = requests.size();
				responseFiles = openAI.processBatch(new BuildReport(), requests, false);
			} else {
				responseFiles = Arrays.asList(RESUME_FILES);
			}
			
			int processed = importResponses(dataset, expected, responseFiles);
			totalRequests += processed;

			Log.info("Namespace " + dataset.getDescription() + " is done. Updated " + processed + " bill interpretations.");
		}

		Log.info("Bill interpretation topic patch complete. Updated " + totalRequests + " bill interpretations.");
	}

	private boolean hasTopicFields(BillInterpretation interp) {
		return interp.getTopics() != null && interp.getOtherNames() != null;
	}

	private InterpretationRequest createRequest(Bill bill, BillText billText) {
		String billTextDocument = billText.getDocument();
		boolean truncated = billTextDocument.length() > MAX_BILL_TEXT_CHARS;
		if (truncated) {
			billTextDocument = billTextDocument.substring(0, MAX_BILL_TEXT_CHARS);
		}

		String userMsg = """
				Bill: %s
				Bill text version: %s
				%s
				Official Bill Text:
				%s
				""".formatted(
				bill.getDescription(),
				StringUtils.defaultString(billText.getVersion()),
				truncated ? "NOTE: The bill text has been truncated because it exceeded the input token limit." : "",
				billTextDocument);

		return InterpretationRequest.builder()
				.data(new CustomData(REQUEST_PREFIX + bill.getId()))
				.systemMsg(SYSTEM_PROMPT)
				.userMsg(userMsg)
				.requestedModel(MODEL)
				.build();
	}

	@SneakyThrows
	private int importResponses(PoliscoreDatasetIF dataset, int expectedBillResponses, List<File> responseFiles) {
		String namespaceDescription = dataset.getDescription();
		int processed = 0;
		for (File responseFile : responseFiles) {
			Log.info("Importing topic patch responses from " + responseFile.getAbsolutePath());
			@Cleanup BufferedReader reader = new BufferedReader(new FileReader(responseFile));
			
			long lineNum = 1;
			String line = reader.readLine();
			while (line != null) {
				try {
					if (processLine(dataset, line)) {
						processed++;
						if (processed % 10 == 0) {
							Log.info("Processed " + processed + " of " + expectedBillResponses + " bills in namespace " + namespaceDescription);
						}
					}
				} catch (Throwable t) {
					Log.error("Encountered error while reading line " + lineNum + ".", t);
				}
				line = reader.readLine();
				lineNum++;
			}
		}

		if (processed != expectedBillResponses) {
			Log.warn("Expected " + expectedBillResponses + " bill topic responses in namespace " + namespaceDescription + " but processed " + processed + ".");
		}
		return processed;
	}

	private boolean processLine(PoliscoreDatasetIF dataset, String line) throws Exception {
		BatchOpenAIResponse response = MAPPER.readValue(line, BatchOpenAIResponse.class);
		String oid = response.getCustomData().getOid();
		if (!oid.startsWith(REQUEST_PREFIX)) {
			return false;
		}

		if (response.getError() != null || response.getResponse() == null || response.getResponse().getStatus_code() >= 400) {
			throw new RuntimeException("OpenAI topic patch response failed for " + oid + ": " + response.getError());
		}

		String billId = oid.substring(REQUEST_PREFIX.length());
		String content = response.getResponse().getBody().getChoices().get(0).getMessage().getContent();
		TopicPatchPayload payload = parsePayload(content);

		Bill bill = dataset.get(billId, Bill.class).get();
		BillInterpretation interp = billService.getInterpretation(bill).get();

		interp.setTopics(TopicCanonicalizer.canonicalizeTopics(payload.topics()));
		interp.setOtherNames(cleanList(payload.otherNames()));
		s3.put(interp);
		return true;
	}

	private TopicPatchPayload parsePayload(String content) throws Exception {
		String json = extractJsonArray(content);
		List<TopicPatchPayload> payloads = MAPPER.readValue(json, new TypeReference<List<TopicPatchPayload>>() {});
		if (payloads.size() != 1) {
			throw new RuntimeException("Expected exactly one topic patch object, got " + payloads.size() + ": " + content);
		}
		return payloads.get(0);
	}

	private String extractJsonArray(String content) {
		String cleaned = StringUtils.defaultString(content)
				.replace("```json", "")
				.replace("```JSON", "")
				.replace("```", "")
				.strip();
		int start = cleaned.indexOf('[');
		int end = cleaned.lastIndexOf(']');
		if (start < 0 || end < start) {
			throw new RuntimeException("Unable to find JSON array in OpenAI response: " + content);
		}
		return cleaned.substring(start, end + 1);
	}

	private List<String> cleanList(List<String> incoming) {
		if (incoming == null) {
			return new ArrayList<>();
		}

		return incoming.stream()
				.map(StringUtils::trimToNull)
				.filter(value -> value != null)
				.distinct()
				.toList();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record TopicPatchPayload(List<String> topics, List<String> otherNames) {
	}

	@Override
	public int run(String... args) throws Exception {
		process();

		Quarkus.waitForExit();
		return 0;
	}

	public static void main(String[] args) {
		Quarkus.run(BillInterpretationTopicPatcher.class, args);
		Quarkus.asyncExit(0);
	}
}
