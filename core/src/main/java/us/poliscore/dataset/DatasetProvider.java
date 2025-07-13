package us.poliscore.dataset;

import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.LegislativeSession;

public interface DatasetProvider {
	
	public PoliscoreDataset importDataset(DeploymentConfig ref);
	
	public LegislativeSession getPreviousSession(LegislativeSession current);
	
	public void syncS3LegislatorImages(PoliscoreDataset dataset);
	
	public void syncS3BillText(PoliscoreDataset dataset);
	
}
