package us.poliscore.service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.dataset.DatasetProvider;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.BuildReport;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Persistable;

@ApplicationScoped
public class GovernmentDataService {

	private static final Logger LOGGER = LoggerFactory.getLogger(GovernmentDataService.class);
	
	@Inject
	private DatasetProvider provider;
	
	@Inject private PoliscoreConfigService config;
	
	private static List<PoliscoreDatasetIF> importedDatasets = new ArrayList<PoliscoreDatasetIF>();
	
	private static boolean didImportDatasets = false;
	
	public synchronized List<PoliscoreDatasetIF> importAllDatasets() {
		return importAllDatasets(null);
	}
	
	public synchronized List<PoliscoreDatasetIF> importAllDatasets(BuildReport report) {
		if (didImportDatasets) return importedDatasets;
		
		for (val cfg : config.getSupportedDeployments()) {
			importDataset(cfg, report);
		}
		
		didImportDatasets = true;
		
		SessionInfoService.buildSessions(importedDatasets);
		
		return importedDatasets;
	}
	
	private static void rethrow(Throwable t) {
		if (t instanceof RuntimeException runtimeException) {
			throw runtimeException;
		}
		if (t instanceof Error error) {
			throw error;
		}
		throw new RuntimeException(t);
	}

	public synchronized void resetImports() {
		importedDatasets = new ArrayList<PoliscoreDatasetIF>();
		didImportDatasets = false;
	}
	
	public PoliscoreDatasetIF importDataset(LegislativeNamespace namespace, int year) {
		return importDataset(new DeploymentConfig(namespace, year));
	}
	
	public PoliscoreDatasetIF importDataset(DeploymentConfig ref) {
		return importDataset(ref, null);
	}

	public PoliscoreDatasetIF importDataset(DeploymentConfig ref, BuildReport report) {
		// If it's already been imported, just return it
		for (val dataset : importedDatasets) {
			if (dataset.getNamespace().equals(ref.getNamespace()) && ref.getYear().equals(dataset.getEndYear())) {
				return dataset;
			}
		}
		
		var dataset = provider.importDataset(ref, report);
		
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
	
	public PoliscoreDatasetIF getPreviousDataset(PoliscoreDatasetIF dataset) {
		for (val loopDs : importedDatasets) {
			if (dataset.getNamespace().equals(loopDs.getNamespace()) && loopDs.isYearWithin(dataset.getStartYear()-1)) {
				return loopDs;
			}
		}
		
		return null;
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
		return importedDatasets.stream().filter(d -> d.getConfig().getBuild()).toList();
	}
	
//	public LegislativeSession getPreviousRegularSession(LegislativeSession current) {
//		return provider.getPreviousRegularSession(current);
//	}

	public PoliscoreDatasetIF getMostRecentDataset(LegislativeNamespace namespace) {
		PoliscoreDatasetIF mostRecent = null;
		
		for (val dataset : importedDatasets) {
			if (dataset.getNamespace().equals(namespace) && (mostRecent == null || dataset.getEndYear() > mostRecent.getEndYear()))
				mostRecent = dataset;
		}
		
		return mostRecent;
	}
}
