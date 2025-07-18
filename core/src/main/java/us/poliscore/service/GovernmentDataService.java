package us.poliscore.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.PoliscoreCompositeDataset;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.dataset.DatasetProvider;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;

@ApplicationScoped
public class GovernmentDataService {
	
	@Inject
	private DatasetProvider provider;
	
	@Inject private PoliscoreConfigService config;
	
	private static List<PoliscoreDataset> importedDatasets = new ArrayList<PoliscoreDataset>();
	
	private static boolean didImportDatasets = false;
	
	private static PoliscoreDataset workingDataset;
	
	public List<PoliscoreDataset> importAllDatasets() {
		if (didImportDatasets) return importedDatasets;
		
		for (val cfg : config.getSupportedDeployments()) {
			importDataset(cfg);
		}
		
		didImportDatasets = true;
		
		return importedDatasets;
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
	
	public PoliscoreDataset getDataset(String poliscoreObjectId) {
		String sessionKey = poliscoreObjectId.split("/")[1] + "/" + poliscoreObjectId.split("/")[2] + "/" + poliscoreObjectId.split("/")[3];
		
		for (val dataset : importedDatasets) {
			if (dataset.getSession().getKey().equals(sessionKey))
				return dataset;
		}
		
		throw new NoSuchElementException();
	}
	
	public PoliscoreDataset getDataset(LegislativeNamespace namespace, int year) {
		for (val dataset : importedDatasets) {
			if (dataset.getSession().getNamespace().equals(namespace) && dataset.getSession().isYearWithin(year))
				return dataset;
		}
		
		throw new NoSuchElementException();
	}
	
	public PoliscoreDataset getDataset(LegislativeNamespace namespace, String sessionCode) {
		for (val dataset : importedDatasets) {
			if (dataset.getSession().getNamespace().equals(namespace) && dataset.getSession().getCode().equals(sessionCode))
				return dataset;
		}
		
		throw new NoSuchElementException();
	}
	
	public void syncS3LegislatorImages(PoliscoreDataset dataset) {
		provider.syncS3LegislatorImages(dataset);
	}
	
	public void syncS3BillText(PoliscoreDataset dataset) {
		provider.syncS3BillText(dataset);
	}
	
	public PoliscoreCompositeDataset getAllDataset() {
		return new PoliscoreCompositeDataset(importedDatasets);
	}
	
	public List<PoliscoreDataset> getAllImportedDatasets() {
		return importedDatasets;
	}
	
	public List<PoliscoreDataset> getBuildDatasets() {
		val now = LocalDate.now();
		
		return importedDatasets.stream()
				.filter(ds -> !now.isBefore(ds.getSession().getStartDate()) && !now.isAfter(ds.getSession().getEndDate()))
				.toList();
	}
	
	public LegislativeSession getPreviousSession(LegislativeSession current) {
		return provider.getPreviousSession(current);
	}
}
