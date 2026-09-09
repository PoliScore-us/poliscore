package us.poliscore.entrypoint;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class DatabaseBuilderConfigTest {

	@Test
	void staleBillAnalysisRefreshIsDisabledByDefault() {
		assertFalse(new DatabaseBuilderConfig().isRefreshStaleBillAnalyses());
	}
}
