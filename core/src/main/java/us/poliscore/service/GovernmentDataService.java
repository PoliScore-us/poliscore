package us.poliscore.service;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.val;
import us.poliscore.PoliscoreCompositeDataset;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.dataset.DatasetProvider;
import us.poliscore.model.LegislativeSession;

@ApplicationScoped
public class GovernmentDataService {
	
	@Inject
	private DatasetProvider provider;
	
	@Inject private PoliscoreConfigService config;
	
	private List<PoliscoreDataset> importedDatasets = new ArrayList<PoliscoreDataset>();
	
	public List<PoliscoreDataset> importDatasets() {
		importedDatasets = new ArrayList<PoliscoreDataset>();
		
		for (val cfg : config.getSupportedDeployments()) {
			importDataset(cfg);
		}
		
		return importedDatasets;
	}
	
	public PoliscoreDataset importDataset() {
		return importDataset(config.getDeployment());
	}
	
	public PoliscoreDataset importDataset(DeploymentConfig ref) {
		// If it's already been imported, just return it
		for (val dataset : importedDatasets) {
			if (dataset.getSession().getNamespace().equals(ref.getNamespace()) && ref.getYear().equals(dataset.getSession().getEndDate().getYear())) {
				return dataset;
			}
		}
		
		var dataset = provider.importDataset(ref);
		
		importedDatasets.add(dataset);
		
		return dataset;
	}
	
	public void syncS3LegislatorImages(PoliscoreDataset dataset) {
		provider.syncS3LegislatorImages(dataset);
	}
	
	public void syncS3BillText(PoliscoreDataset dataset) {
		provider.syncS3BillText(dataset);
	}
	
	public PoliscoreDataset getDeploymentDataset() {
		return importedDatasets.get(0);
	}
	
	public PoliscoreCompositeDataset getAllDataset() {
		return new PoliscoreCompositeDataset(importedDatasets);
	}
	
	// Alias
	public PoliscoreDataset getDataset() { return getDeploymentDataset(); }
	
	// Alias
	public LegislativeSession getSession() { return getDataset().getSession(); }
	
	public LegislativeSession getPreviousSession() {
		return provider.getPreviousSession(getSession());
	}
}
