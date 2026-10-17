package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void regenerationPreservesCalendarMetadata(@TempDir Path directory) throws Exception {
        var path = directory.resolve("sessions.json").toFile();
        var original = new LegislativeSession(true, LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31), "test", LegislativeNamespace.US_COLORADO);
        var calendar = List.of(new LegislativeSession.SessionCalendar("Regular session",
                "2025-01-08", "2025-05-07", "https://example.gov/source",
                "https://example.gov/calendar", "calendar"));
        original.setCalendar(calendar);
        SessionInfoService.writeSessionMetadata(List.of(original), path);

        var regenerated = new LegislativeSession(true, LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31), "test", LegislativeNamespace.US_COLORADO);
        SessionInfoService.writeSessionMetadata(List.of(regenerated), path);
        var saved = SessionInfoService.mapper().readValue(path, LegislativeSession[].class)[0];
        assertEquals(calendar, saved.getCalendar());
        assertEquals(LocalDate.of(2025, 1, 1), saved.getStartDate());
    }

	private PoliscoreDataset dataset(DeploymentConfig config, boolean regular, String code) {
		var session = new LegislativeSession(regular, LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 12, 31), code, LegislativeNamespace.US_COLORADO);
		return new PoliscoreDataset(session, config);
	}
}
