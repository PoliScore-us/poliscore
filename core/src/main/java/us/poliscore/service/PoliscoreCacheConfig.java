package us.poliscore.service;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.annotations.StaticInitSafe;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@Unremovable
public class PoliscoreCacheConfig {

	@ConfigProperty(name = "poliscore.cache")
	@StaticInitSafe
	String configuredCachePath;

	public String cachePath() {
		String trimmed = configuredCachePath == null ? null : configuredCachePath.trim();
		if (StringUtils.isBlank(trimmed)) {
			throw new IllegalStateException("Quarkus property 'poliscore.cache' resolved to a blank value.");
		}
		return trimmed;
	}
}
