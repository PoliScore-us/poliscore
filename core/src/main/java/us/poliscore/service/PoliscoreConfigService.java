package us.poliscore.service;

import java.util.Arrays;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.LegislativeNamespace;

@ApplicationScoped
public class PoliscoreConfigService {
	
	@ConfigProperty(name = "deployment.namespace")
	protected String namespace;
	
	@ConfigProperty(name = "deployment.year")
	protected int year;
	
	public DeploymentConfig getDeployment() {
		return new DeploymentConfig(LegislativeNamespace.of(namespace), year);
	}
	
	public List<DeploymentConfig> getSupportedDeployments() {
		return Arrays.asList(getDeployment(), new DeploymentConfig(LegislativeNamespace.US_CONGRESS, 2024));
	}
	
}
