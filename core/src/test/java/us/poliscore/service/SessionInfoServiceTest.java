package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.poliscore.PoliscoreCompositeDataset;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;

class SessionInfoServiceTest {

	@Test
	void extractsEveryChildSessionFromCompositeDataset() {
		var config = new DeploymentConfig(LegislativeNamespace.US_COLORADO, 2026, 1.0f, true);
		var regular = dataset(config, true, "2243");
		var special = dataset(config, false, "2299");
		var composite = new PoliscoreCompositeDataset(config, List.of(regular, special));

		var sessions = SessionInfoService.sessionsForDatasets(List.of(composite));

		assertEquals(List.of("us/co/2243", "us/co/2299"), sessions.stream().map(LegislativeSession::getKey).toList());
	}

	private PoliscoreDataset dataset(DeploymentConfig config, boolean regular, String code) {
		var session = new LegislativeSession(regular, LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 12, 31), code, LegislativeNamespace.US_COLORADO);
		return new PoliscoreDataset(session, config);
	}
}
