package us.poliscore.dataset;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DatasetReference;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;

@Priority(1)
@Alternative
@ApplicationScoped
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
	public PoliscoreDataset importDataset(DatasetReference ref) {
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
