package us.poliscore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DatasetReference;
import us.poliscore.PoliscoreUtil;
import us.poliscore.dataset.DatasetProvider;
import us.poliscore.model.LegislativeSession;

@ApplicationScoped
public class GovernmentDataService {
	
	@Inject
	private DatasetProvider provider;
	
	private PoliscoreDataset deploymentDataset;
	
	public PoliscoreDataset importDataset() {
		return importDataset(PoliscoreUtil.DEPLOYMENT_DATASET);
	}
	
	public PoliscoreDataset importDataset(DatasetReference ref) {
		if (PoliscoreUtil.DEPLOYMENT_DATASET.equals(ref) && deploymentDataset != null) { return deploymentDataset; }
		
		var dataset = provider.importDataset(ref);
		
		if (PoliscoreUtil.DEPLOYMENT_DATASET.equals(ref)) {
			deploymentDataset = dataset;
		}
		
		return dataset;
	}
	
	public void syncS3LegislatorImages(PoliscoreDataset dataset) {
		provider.syncS3LegislatorImages(dataset);
	}
	
	public void syncS3BillText(PoliscoreDataset dataset) {
		provider.syncS3BillText(dataset);
	}
	
	public PoliscoreDataset getDeploymentDataset() {
		return deploymentDataset;
	}
	
	// Alias
	public PoliscoreDataset getDataset() { return deploymentDataset; }
	
	// Alias
	public LegislativeSession getSession() { return getDataset().getSession(); }
	
	public LegislativeSession getPreviousSession() {
		return provider.getPreviousSession(getSession());
	}
}
