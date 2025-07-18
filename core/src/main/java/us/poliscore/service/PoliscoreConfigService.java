package us.poliscore.service;

import java.util.Arrays;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.val;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.LegislativeNamespace;

@ApplicationScoped
public class PoliscoreConfigService {
	
	private static List<DeploymentConfig> SUPPORTED_DEPLOYMENTS = Arrays.asList(
			new DeploymentConfig(LegislativeNamespace.US_CONGRESS, 2026), new DeploymentConfig(LegislativeNamespace.US_CONGRESS, 2024),
			new DeploymentConfig(LegislativeNamespace.US_COLORADO, 2025)
	);
	
//	@ConfigProperty(name = "deployment.namespace")
//	protected String namespace;
//	
//	@ConfigProperty(name = "deployment.year")
//	protected int year;
	
//	public DeploymentConfig getDeployment() {
//		return new DeploymentConfig(LegislativeNamespace.of(namespace), year);
//	}
	
	public List<DeploymentConfig> getSupportedDeployments() {
//		if (!SUPPORTED_DEPLOYMENTS.stream().anyMatch(dep -> dep.equals(getDeployment())))
//			throw new UnsupportedOperationException("Configured deployment must be in supported deployment list. Add this deployment to PoliscoreConfig.supportedDeployments to continue.");
		
		return SUPPORTED_DEPLOYMENTS;
	}
	
}
