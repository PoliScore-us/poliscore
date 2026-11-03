package us.poliscore.entrypoint;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class DatabaseBuilderConfigTest {

	@Test
	void staleBillAnalysisRefreshIsDisabledByDefault() {
		assertFalse(new DatabaseBuilderConfig().isRefreshStaleBillAnalyses());
	}
    @org.junit.jupiter.api.Test
    void realizedImpactDefaultsOffAndCliCanEnableIt() throws Exception {
        var config = new DatabaseBuilderConfig();
        org.junit.jupiter.api.Assertions.assertFalse(config.isIncludeRealizedSocietalImpact());
        var builder = new DatabaseBuilder();
        var field = DatabaseBuilder.class.getDeclaredField("runtimeConfig");
        field.setAccessible(true);
        field.set(builder, config);
        var method = DatabaseBuilder.class.getDeclaredMethod("applyCliConfiguration");
        method.setAccessible(true);
        var cli = new picocli.CommandLine(builder);
        cli.parseArgs("--include-realized-societal-impact");
        method.invoke(builder);
        org.junit.jupiter.api.Assertions.assertTrue(config.isIncludeRealizedSocietalImpact());
        cli.parseArgs("--no-include-realized-societal-impact");
        method.invoke(builder);
        org.junit.jupiter.api.Assertions.assertFalse(config.isIncludeRealizedSocietalImpact());
    }
}
