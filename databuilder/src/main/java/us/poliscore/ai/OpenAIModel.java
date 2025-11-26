package us.poliscore.ai;

import java.util.NoSuchElementException;
import com.openai.models.ReasoningEffort;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OpenAIModel {

    GPT51("gpt-5.1", 400_000, 128_000, false, true, ReasoningEffort.MEDIUM, new RateLimit(40_000_000, 15_000)),
    GPT5("gpt-5", 400_000, 128_000, false, true, ReasoningEffort.MEDIUM, new RateLimit(40_000_000, 15_000)),
    GPT41("gpt-4.1", 950_000, 32_768, true, true, null, new RateLimit(30_000_000, 10_000)),
    GPT41mini("gpt-4.1-mini", GPT41.contextWindowTokens, GPT41.maxOutputTokens, true, false, null, new RateLimit(150_000_000, 30_000)),
    GPT4o("gpt-4o", 122_500, 14_000, true, true, null, new RateLimit(30_000_000, 10_000)),
    O3("o3", 190_000, 95_000, false, true, ReasoningEffort.MEDIUM, new RateLimit(30_000_000, 10_000)),
    O3_DEEP_RESEARCH("o3-deep-research", 190_000, 95_000, false, true, null, new RateLimit(30_000_000, 10_000));
	
	public static final OpenAIModel DEFAULT_MODEL = GPT51;

    // ---- Fields ----
    private final String id;
    private final int contextWindowTokens;
    private final int maxOutputTokens;
    private final boolean supportsTemperature;
    private final boolean supportsSearch;
    private final ReasoningEffort reasoningEffort;
    private final RateLimit rateLimit;

    public int getContextWindowStringLength() {
        return contextWindowTokens * 4;
    }
    
    @Override
    public String toString() {
    	return id;
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
        private final int tpm;
        private final int rpm;
    }
}
