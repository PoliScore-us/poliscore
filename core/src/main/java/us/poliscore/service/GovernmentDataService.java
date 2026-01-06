package us.poliscore.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.PoliscoreCompositeDataset;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.dataset.DatasetProvider;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Persistable;

@ApplicationScoped
public class GovernmentDataService {
	
	@Inject
	private DatasetProvider provider;
	
	@Inject private PoliscoreConfigService config;
	
	private static List<PoliscoreDatasetIF> importedDatasets = new ArrayList<PoliscoreDatasetIF>();
	
	private static boolean didImportDatasets = false;
	
	public List<PoliscoreDatasetIF> importAllDatasets() {
		if (didImportDatasets) return importedDatasets;
		
		for (val cfg : config.getSupportedDeployments()) {
			importDataset(cfg);
		}
		
		didImportDatasets = true;
		
		return importedDatasets;
	}
	
	public PoliscoreDatasetIF importDataset(LegislativeNamespace namespace, int year) {
		return importDataset(new DeploymentConfig(namespace, year));
	}
	
	public PoliscoreDatasetIF importDataset(DeploymentConfig ref) {
		// If it's already been imported, just return it
		for (val dataset : importedDatasets) {
			if (dataset.getNamespace().equals(ref.getNamespace()) && ref.getYear().equals(dataset.getEndYear())) {
				return dataset;
			}
		}
		
		var dataset = provider.importDataset(ref);
		
		importedDatasets.add(dataset);
		
		return dataset;
	}
	
	public PoliscoreDatasetIF getDataset(String poliscoreObjectId) {
		String sessionKey = poliscoreObjectId.split("/")[1] + "/" + poliscoreObjectId.split("/")[2] + "/" + poliscoreObjectId.split("/")[3];
		
		for (val dataset : importedDatasets) {
			if (dataset.containsSession(sessionKey))
				return dataset;
		}
		
		throw new NoSuchElementException();
	}
	
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz) {
		return this.getDataset(id).get(id, clazz);
	}
	
	public <T extends Persistable> boolean exists(String id, Class<T> clazz) {
		return this.getDataset(id).exists(id, clazz);
	}
	
	public PoliscoreDatasetIF getDataset(LegislativeNamespace namespace, int year) {
		for (val dataset : importedDatasets) {
			if (dataset.getNamespace().equals(namespace) && dataset.isYearWithin(year))
				return dataset;
		}
		
		throw new NoSuchElementException();
	}
	
	public PoliscoreDatasetIF getDataset(LegislativeNamespace namespace, String sessionKey) {
		for (val dataset : importedDatasets) {
			if (dataset.getNamespace().equals(namespace) && dataset.containsSession(sessionKey))
				return dataset;
		}
		
		throw new NoSuchElementException();
	}
	
	public PoliscoreDatasetIF getDataset(LegislativeSession session) {
		return getDataset(session.getNamespace(), session.getKey());
	}
	
	public void syncS3LegislatorImages(PoliscoreDatasetIF dataset) {
		provider.syncS3LegislatorImages(dataset);
	}
	
	public void syncS3BillText(PoliscoreDatasetIF dataset) {
		provider.syncS3BillText(dataset);
	}
	
	public List<PoliscoreDatasetIF> getAllImportedDatasets() {
		return importedDatasets;
	}
	
	public List<PoliscoreDatasetIF> getBuildDatasets() {
		val currentYear = LocalDate.now().getYear();
		
		return importedDatasets.stream()
				.filter(ds -> ds.isYearWithin(currentYear))
				.toList();
	}
	
	public LegislativeSession getPreviousRegularSession(LegislativeSession current) {
		return provider.getPreviousRegularSession(current);
	}
}
