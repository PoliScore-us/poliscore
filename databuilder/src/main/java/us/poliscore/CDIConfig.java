package us.poliscore;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import us.poliscore.dataset.DatasetProvider;
import us.poliscore.dataset.PoliscoreDatasetProvider;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.legiscan.service.LegiscanService;
import us.poliscore.service.SecretService;

@ApplicationScoped
public class CDIConfig {
	
	@Inject
	SecretService secret;
	
	@Inject
	PoliscoreDatasetProvider poliscoreProvider;

	@ConfigProperty(name = "poliscore.legiscan.quota-limit", defaultValue = LegiscanService.DEFAULT_REQUEST_QUOTA_LIMIT_CONFIG_VALUE)
	int legiscanQuotaLimit;
		
	@Produces
	@Singleton
	public CachedLegiscanService produceLegiscanService() {
		return CachedLegiscanService.builder(secret.getLegiscanSecret())
				.withRequestQuotaLimit(legiscanQuotaLimit)
				.withCacheDirectory(PoliscoreUtil.cacheDir("legiscan"))
				.build();
	}
	
}
