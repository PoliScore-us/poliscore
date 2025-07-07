package us.poliscore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DatasetReference;
import us.poliscore.PoliscoreUtil;
import us.poliscore.dataset.DatasetSupplier;
import us.poliscore.model.LegislativeSession;

@ApplicationScoped
public class GovernmentDataService {
	
	@Inject
	private DatasetSupplier supplier;
	
	private PoliscoreDataset deploymentDataset;
	
	public PoliscoreDataset importDataset() {
		return importDataset(PoliscoreUtil.DEPLOYMENT_DATASET);
	}
	
	public PoliscoreDataset importDataset(DatasetReference ref) {
		if (PoliscoreUtil.DEPLOYMENT_DATASET.equals(ref) && deploymentDataset != null) { return deploymentDataset; }
		
		var dataset = supplier.importDataset(ref);
		
		if (PoliscoreUtil.DEPLOYMENT_DATASET.equals(ref)) {
			deploymentDataset = dataset;
		}
		
		return dataset;
	}
	
	public PoliscoreDataset getDeploymentDataset() {
		return deploymentDataset;
	}
	
	// Alias
	public PoliscoreDataset getDataset() { return deploymentDataset; }
	
	// Alias
	public LegislativeSession getSession() { return getDataset().getSession(); }
	
	public LegislativeSession getPreviousSession() {
		return supplier.getPreviousSession(getSession());
	}
}
