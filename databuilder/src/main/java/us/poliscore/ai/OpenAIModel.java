package us.poliscore.ai;

import java.util.NoSuchElementException;

import lombok.AllArgsConstructor;
import lombok.Getter;
import us.poliscore.service.OpenAIService.Usage;

@Getter
@AllArgsConstructor
public enum OpenAIModel {

	GPT52("gpt-5.2", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 0.875, 7.00),
	GPT51("gpt-5.1", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 0.625, 5.00),
	GPT5("gpt-5", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 0.625, 5.00),
	GPT5mini("gpt-5-mini", 400_000, 128_000, false, true, true, new RateLimit(40_000_000, 15_000), 0.125, 1.00),
	GPT41("gpt-4.1", 950_000, 32_768, true, true, false, new RateLimit(30_000_000, 10_000), 0, 0),
	GPT41mini("gpt-4.1-mini", GPT41.contextWindowTokens, GPT41.maxOutputTokens, true, false, false, new RateLimit(150_000_000, 30_000), 0, 0),
	GPT4o("gpt-4o", 122_500, 14_000, true, true, false, new RateLimit(30_000_000, 10_000), 0, 0),
	O3("o3", 190_000, 95_000, false, true, true, new RateLimit(30_000_000, 10_000), 0, 0),
	O3_DEEP_RESEARCH("o3-deep-research", 190_000, 95_000, false, true, true, new RateLimit(30_000_000, 10_000), 0, 0);

	public static final OpenAIModel DEFAULT_MODEL = GPT51;

	public static final OpenAIModel DEFAULT_MODEL_MINI = GPT5mini;

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

	public int getContextWindowStringLength() {
		return contextWindowTokens * 2; // TODO : Technically this should work at *4 ? But in practice I'm not seeing it
										// work until we get to *2.
	}

	@Override
	public String toString() {
		return id;
	}

	public double estimateCostUsd(Usage usage) {
		if (usage == null)
			return 0.0d;
		return (usage.promptTokens() / 1_000_000d) * inputUsdPer1M
				+ (usage.completionTokens() / 1_000_000d) * outputUsdPer1M;
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
