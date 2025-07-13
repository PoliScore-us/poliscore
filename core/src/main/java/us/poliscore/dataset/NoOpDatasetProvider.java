package us.poliscore.dataset;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.LegislativeSession;

@ApplicationScoped
@Named("noop")
@DefaultBean
public class NoOpDatasetProvider implements DatasetProvider {

	@Override
	public PoliscoreDataset importDataset(DeploymentConfig ref) {
		return null;
	}

	@Override
	public LegislativeSession getPreviousSession(LegislativeSession current) {
		return null;
	}

	@Override
	public void syncS3LegislatorImages(PoliscoreDataset dataset) {
		
	}

	@Override
	public void syncS3BillText(PoliscoreDataset dataset) {
		
	}
	
}
