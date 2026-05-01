package us.poliscore.ai;

import java.util.NoSuchElementException;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import lombok.AllArgsConstructor;
import lombok.Getter;
import us.poliscore.service.OpenAIService.Usage;

@Getter
@AllArgsConstructor
public enum OpenAIModel {
	GPT55("gpt-5.5", 1_050_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 5.0, 30),
	GPT54mini("gpt-5.4-mini", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 0.75, 4.5),
	GPT54nano("gpt-5.4-nano", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 0.2, 1.25),
	GPT54("gpt-5.4", 1_050_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 2.5, 15),
	GPT52("gpt-5.2", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 1.7, 14),
	GPT51("gpt-5.1", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 1.3, 10),
	GPT5("gpt-5", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 1.3, 10),
	GPT5mini("gpt-5-mini", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 0.25, 2.00),
	GPT5nano("gpt-5-nano", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 0.05, 0.4),
	GPT41("gpt-4.1", 1_047_576, 32_768, true, true, false, new RateLimit(30_000_000, 10_000), 0, 0),
	GPT41mini("gpt-4.1-mini", GPT41.contextWindowTokens, GPT41.maxOutputTokens, true, false, false, new RateLimit(150_000_000, 30_000), 0, 0),
	GPT4o("gpt-4o", 122_500, 14_000, true, true, false, new RateLimit(30_000_000, 10_000), 0, 0),
	O3("o3", 190_000, 95_000, false, true, true, new RateLimit(30_000_000, 10_000), 0, 0),
	O3_DEEP_RESEARCH("o3-deep-research", 190_000, 95_000, false, true, true, new RateLimit(30_000_000, 10_000), 0, 0);

	public static final OpenAIModel DEFAULT_MODEL = GPT55;

	public static final OpenAIModel DEFAULT_MODEL_MINI = GPT5mini;
	
	// Because we have a reasoning model, this max output is important because it gets fed back into the input again, reducing our potential max input tokens
	public static final int MAX_OUTPUT_TOKENS = 100_000;

	// ---- Fields ----
	private final String id;
	private final int contextWindowTokens;
	private final int maxOutputTokens;
	private final boolean supportsTemperature;
	private final boolean supportsSearch;
	private final boolean supportsReasoning;
	private final RateLimit rateLimit;
	private final double inputUsdPer1M;
	private final double outputUsdPer1M;
	
	public int getContextWindowTokens() {
		// We're dividing this in half because we need to reserve room for internal reasoning as well as web search tooling.
		return this.contextWindowTokens / 2;
	}

	public int getContextWindowStringLength() {
		return contextWindowTokens * 2; // TODO : Technically this should work at *4 ? But in practice I'm not seeing it
										// work until we get to *2.
	}

	@Override
	public String toString() {
		return id;
	}

	public double estimateCostUsd(Usage usage, boolean batchOrFlex) {
		if (usage == null)
			return 0.0d;
		
		// If we requested flex, but OpenAI didn't honor our request, then we get charged double...
		double inputPrice = "normal".equals(usage.actualServiceTier()) ? 2*inputUsdPer1M : inputUsdPer1M;
		double outputPrice = "normal".equals(usage.actualServiceTier()) ? 2*outputUsdPer1M : outputUsdPer1M;
		
		double cost = (usage.promptTokens() / 1_000_000d) * inputPrice
				+ (usage.completionTokens() / 1_000_000d) * outputPrice;
		
		if (batchOrFlex)
			return cost / 2;
		else
			return cost;
	}

	// ---- Lookup by id ----
	public static OpenAIModel fromString(String id) {
		for (OpenAIModel m : values()) {
			if (m.id.equals(id)) {
				return m;
			}
		}
		throw new NoSuchElementException(id);
	}

	@Getter
	@AllArgsConstructor
	public static class RateLimit {
		private final long tpm;
		private final long rpm;
	}
}
