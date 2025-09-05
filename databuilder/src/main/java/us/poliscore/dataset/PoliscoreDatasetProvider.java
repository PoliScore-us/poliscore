package us.poliscore.dataset;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
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
	
	protected DatasetProvider getProvider(LegislativeNamespace namespace) {
		if (namespace == null || namespace.equals(LegislativeNamespace.US_CONGRESS)) {
			return usc;
		} else {
			return legiscan;
		}
	}
	
	@Override
	public PoliscoreDatasetIF importDataset(DeploymentConfig ref) {
		return getProvider(ref.getNamespace()).importDataset(ref);
	}

	@Override
	public LegislativeSession getPreviousRegularSession(LegislativeSession current) {
		return getProvider(current.getNamespace()).getPreviousRegularSession(current);
	}

	@Override
	public void syncS3LegislatorImages(PoliscoreDatasetIF dataset) {
		getProvider(dataset.getNamespace()).syncS3LegislatorImages(dataset);
	}

	@Override
	public void syncS3BillText(PoliscoreDatasetIF dataset) {
		getProvider(dataset.getNamespace()).syncS3BillText(dataset);
	}

}
