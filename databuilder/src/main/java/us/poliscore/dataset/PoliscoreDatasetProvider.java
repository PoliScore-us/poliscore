package us.poliscore.dataset;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.BuildReport;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;

@ApplicationScoped
@Default
@Named("poliscore")
public class PoliscoreDatasetProvider implements DatasetProvider {

	@Inject
	protected LegiscanDatasetProvider legiscan;
	
	@Inject
	protected USCDatasetProvider usc;

	@ConfigProperty(name = "poliscore.environment", defaultValue = "local")
	String environment;

	@ConfigProperty(name = "poliscore.congress-data-provider", defaultValue = "auto")
	String congressDataProvider;
	
	protected DatasetProvider getProvider(LegislativeNamespace namespace) {
		if (namespace == null || namespace.equals(LegislativeNamespace.US_CONGRESS)) {
			if (useUscForCongress()) return usc;
			return legiscan;
		} else {
			return legiscan;
		}
	}

	protected boolean useUscForCongress() {
		if ("prod".equalsIgnoreCase(environment)) return false;
		if ("usc".equalsIgnoreCase(congressDataProvider)) return true;
		if ("legiscan".equalsIgnoreCase(congressDataProvider)) return false;

//		return usc.isEnabled();
		return false;
	}
	
	@Override
	public PoliscoreDatasetIF importDataset(DeploymentConfig ref) {
		return getProvider(ref.getNamespace()).importDataset(ref);
	}

	@Override
	public PoliscoreDatasetIF importDataset(DeploymentConfig ref, BuildReport report) {
		return getProvider(ref.getNamespace()).importDataset(ref, report);
	}

//	@Override
//	public LegislativeSession getPreviousRegularSession(LegislativeSession current) {
//		return getProvider(current.getNamespace()).getPreviousRegularSession(current);
//	}

	@Override
	public void syncS3LegislatorImages(PoliscoreDatasetIF dataset) {
		getProvider(dataset.getNamespace()).syncS3LegislatorImages(dataset);
	}

	@Override
	public void syncS3BillText(PoliscoreDatasetIF dataset) {
		getProvider(dataset.getNamespace()).syncS3BillText(dataset);
	}

}
