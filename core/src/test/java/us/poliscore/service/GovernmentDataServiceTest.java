package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.dataset.DatasetProvider;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.BuildReport;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;

class GovernmentDataServiceTest {

	@AfterEach
	void resetStaticImports() {
		new GovernmentDataService().resetImports();
	}

	@Test
	void importsNearestConfiguredPredecessorAsContext() throws Exception {
		var currentConfig = config(LegislativeNamespace.US_CONGRESS, 2026, true);
		var previousConfig = config(LegislativeNamespace.US_CONGRESS, 2024, false);
		var olderConfig = config(LegislativeNamespace.US_CONGRESS, 2022, false);
		var otherNamespace = config(LegislativeNamespace.US_COLORADO, 2025, false);
		var configService = new PoliscoreConfigService();
		set(configService, "supportedDeployments", List.of(currentConfig, previousConfig, olderConfig, otherNamespace));

		var service = new GovernmentDataService();
		set(service, "config", configService);
		set(service, "provider", new StubProvider());
		var current = service.importDataset(currentConfig, new BuildReport());

		var previous = service.importPreviousDataset(current, new BuildReport()).orElseThrow();

		assertEquals(2024, previous.getConfig().getYear());
		assertEquals(List.of(2026, 2024), service.getAllImportedDatasets().stream()
				.map(dataset -> dataset.getConfig().getYear()).toList());
	}

	private static DeploymentConfig config(LegislativeNamespace namespace, int year, boolean build) {
		return new DeploymentConfig(namespace, year, 1.0f, build);
	}

	private static void set(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static final class StubProvider implements DatasetProvider {
		@Override
		public PoliscoreDatasetIF importDataset(DeploymentConfig ref) {
			int startYear = ref.getNamespace().equals(LegislativeNamespace.US_CONGRESS) ? ref.getYear() - 1 : ref.getYear();
			var session = new LegislativeSession(true, LocalDate.of(startYear, 1, 1),
					LocalDate.of(ref.getYear(), 12, 31), String.valueOf(ref.getYear()), ref.getNamespace());
			return new PoliscoreDataset(session, ref);
		}

		@Override public void syncS3LegislatorImages(PoliscoreDatasetIF dataset) { }
		@Override public void syncS3BillText(PoliscoreDatasetIF dataset) { }
	}
}
