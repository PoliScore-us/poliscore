package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpenAIServiceTest {

	private final OpenAIService service = new OpenAIService();

	@Test
	void doesNotRetryWhenOpenAiCreditsAreExhausted() {
		var failure = new RuntimeException(
				"429: You have no credits remaining. Add credits to continue using the API at "
						+ "https://platform.openai.com/settings/organization/billing/.");

		assertFalse(service.isRateLimitFailure(failure));
	}

	@Test
	void doesNotRetryPermanentQuotaFailureHiddenInCause() {
		var failure = new RuntimeException("429: Rate limit reached",
				new RuntimeException("insufficient_quota: You exceeded your current quota"));

		assertFalse(service.isRateLimitFailure(failure));
	}

	@Test
	void stillRetriesTransientRateLimits() {
		assertTrue(service.isRateLimitFailure(new RuntimeException("429: Rate limit reached")));
		assertTrue(service.isRateLimitFailure(new RuntimeException("Processing too many requests")));
	}
}
