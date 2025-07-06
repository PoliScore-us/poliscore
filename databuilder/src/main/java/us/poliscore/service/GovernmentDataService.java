package us.poliscore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DatasetReference;
import us.poliscore.PoliscoreUtil;
import us.poliscore.dataset.DatasetImporter;

@ApplicationScoped
public class GovernmentDataService {
	
	@Inject
	private DatasetImporter importer;
	
	private PoliscoreDataset deploymentDataset;
	
	public void importDataset() {
		importDataset(PoliscoreUtil.DEPLOYMENT_DATASET);
	}
	
	public void importDataset(DatasetReference dataset) {
		deploymentDataset = legiscan.importDataset(dataset);
	}
	
	public PoliscoreDataset getDeploymentDataset() {
		return deploymentDataset;
	}
}
