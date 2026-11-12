package us.poliscore.dataset.augmentation;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import us.poliscore.*;
import us.poliscore.dataset.USCDatasetProvider;
import us.poliscore.model.*;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislativeTerm;
import us.poliscore.service.storage.S3PersistenceService;
import us.poliscore.legiscan.view.LegiscanState;

class AugmentationRolloverTest {
    @Test void staleReferencesCannotReplaceANewMembersPrimaryTerm() throws Exception {
        var congress = CongressionalSession.of(120);
        var session = new LegislativeSession(true, congress.getStartDate(), congress.getEndDate(), "120", LegislativeNamespace.US_CONGRESS);
        var data = new PoliscoreDataset(session, new PoliscoreDataset.DeploymentConfig(LegislativeNamespace.US_CONGRESS, 2028, 1f, true)) {
            @Override public <T extends Persistable> void optimizeExists(S3PersistenceService store, Class<T> type) {}
        };
        var leg = new Legislator();
        leg.setId("LEG/us/congress/120/same");
        leg.setName(new Legislator.LegislatorName());
        var primary = new LegislativeTerm(LocalDate.of(2027, 1, 1), LocalDate.of(2028, 12, 31), LegiscanState.COLORADO, "1", Party.DEMOCRAT, LegislativeChamber.LOWER);
        leg.getTerms().add(primary);
        data.put(leg);
        var stale = PoliscoreUtil.getObjectMapper().readValue("""
            {"id":{"bioguide":"same"},"terms":[{"type":"rep","state":"CO","district":1,
            "party":"Democrat","start":"2025-01-03","end":"2027-01-03"}]}
            """, us.poliscore.view.USCLegislatorView.class);
        var augmentor = new PoliscoreDatasetAugmentor();
        augmentor.usc = new USCDatasetProvider() {
            @Override public List<us.poliscore.view.USCLegislatorView> referenceLegislators() { return List.of(stale); }
        };
        augmentor.augmentLegislators(data);
        assertEquals(Set.of(primary), leg.getTerms());
        assertTrue(leg.isMemberOfSession(session));
    }
}
