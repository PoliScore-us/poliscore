package us.poliscore;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.service.SecretService;

@Singleton
public class CDIConfig {
	
	@Inject
    SecretService secret;
	
	@Produces
    @Singleton
    public CachedLegiscanService produceLegiscanService() {
        return CachedLegiscanService.builder(secret.getLegiscanSecret()).build();
    }
	
}
