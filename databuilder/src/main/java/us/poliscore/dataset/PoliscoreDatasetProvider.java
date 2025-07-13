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
	public PoliscoreDataset importDataset(DeploymentConfig ref) {
		return getProvider(ref.getNamespace()).importDataset(ref);
	}

	@Override
	public LegislativeSession getPreviousSession(LegislativeSession current) {
		return getProvider(current.getNamespace()).getPreviousSession(current);
	}

	@Override
	public void syncS3LegislatorImages(PoliscoreDataset dataset) {
		getProvider(dataset.getSession().getNamespace()).syncS3LegislatorImages(dataset);
	}

	@Override
	public void syncS3BillText(PoliscoreDataset dataset) {
		getProvider(dataset.getSession().getNamespace()).syncS3BillText(dataset);
	}

}
