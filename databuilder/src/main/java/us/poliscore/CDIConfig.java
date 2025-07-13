package us.poliscore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import us.poliscore.dataset.DatasetProvider;
import us.poliscore.dataset.PoliscoreDatasetProvider;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.service.SecretService;

@ApplicationScoped
public class CDIConfig {
	
	@Inject
    SecretService secret;
	
	@Inject
    PoliscoreDatasetProvider poliscoreProvider;
	
	@Produces
    @Singleton
    public CachedLegiscanService produceLegiscanService() {
        return CachedLegiscanService.builder(secret.getLegiscanSecret()).build();
    }
	
}
