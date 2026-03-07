package us.poliscore.service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.model.bill.BillPrompt;

@ApplicationScoped
public class TokenEstimatorService {

	private static final int RESERVED_TOKENS = 500;

	private final Encoding encoding =
			Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

	/**
	 * Cache exact full merged prompts too, since some of those may repeat.
	 */
	private final ConcurrentMap<String, Integer> fullPromptCache = new ConcurrentHashMap<>();

	/**
	 * The three system prompts you want to recognize and avoid recounting.
	 */
	private volatile CachedSystemPrompt[] cachedSystemPrompts = new CachedSystemPrompt[0];

	@PostConstruct
	void init() {
		// Replace these with however you currently store/access your 3 system prompts.
		String[] knownSystemPrompts = new String[] {
				BillPrompt.getPromptForBill(false, true),
				BillPrompt.getPromptForBill(true, true),
				BillPrompt.slicePrompt
		};

		cachedSystemPrompts = Arrays.stream(knownSystemPrompts)
				.filter(Objects::nonNull)
				.distinct()
				.map(prompt -> new CachedSystemPrompt(prompt, encoding.countTokens(prompt)))
				.sorted(Comparator.comparingInt((CachedSystemPrompt p) -> p.prompt.length()).reversed())
				.toArray(CachedSystemPrompt[]::new);
	}

	/**
	 * If allPrompts contains both system and user prompt merged together,
	 * this will try to detect a known system prompt prefix and only tokenize
	 * the remainder dynamically.
	 */
	public int estimateTokenCount(String allPrompts) {
		if (allPrompts == null || allPrompts.isEmpty())
			return RESERVED_TOKENS;

		Integer cached = fullPromptCache.get(allPrompts);
		if (cached != null)
			return cached;

		int totalTokens = estimateBaseTokenCount(allPrompts) + RESERVED_TOKENS;

		fullPromptCache.putIfAbsent(allPrompts, totalTokens);
		return totalTokens;
	}

	/**
	 * Better if you have them separately.
	 */
	public int estimateTokenCount(String systemPrompt, String userPrompt) {
		int systemTokens = getSystemPromptTokenCount(systemPrompt);
		int userTokens = encoding.countTokens(userPrompt == null ? "" : userPrompt);
		return systemTokens + userTokens + RESERVED_TOKENS;
	}

	private int estimateBaseTokenCount(String allPrompts) {
		for (CachedSystemPrompt cached : cachedSystemPrompts) {
			if (allPrompts.startsWith(cached.prompt)) {
				String remainder = allPrompts.substring(cached.prompt.length());
				return cached.tokenCount + encoding.countTokens(remainder);
			}
		}

		return encoding.countTokens(allPrompts);
	}

	private int getSystemPromptTokenCount(String systemPrompt) {
		if (systemPrompt == null || systemPrompt.isEmpty())
			return 0;

		for (CachedSystemPrompt cached : cachedSystemPrompts) {
			if (cached.prompt.equals(systemPrompt))
				return cached.tokenCount;
		}

		return encoding.countTokens(systemPrompt);
	}

	public void clearPromptCache() {
		fullPromptCache.clear();
	}

	private static final class CachedSystemPrompt {
		private final String prompt;
		private final int tokenCount;

		private CachedSystemPrompt(String prompt, int tokenCount) {
			this.prompt = prompt;
			this.tokenCount = tokenCount;
		}
	}
}