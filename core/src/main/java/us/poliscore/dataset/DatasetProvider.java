package us.poliscore.dataset;

import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.LegislativeSession;

public interface DatasetProvider {
	
	public PoliscoreDatasetIF importDataset(DeploymentConfig ref);
	
//	public PoliscoreDatasetIF getPreviousDataset(PoliscoreDatasetIF dataset);
	
//	public LegislativeSession getPreviousRegularSession(LegislativeSession current);
	
	public void syncS3LegislatorImages(PoliscoreDatasetIF dataset);
	
	public void syncS3BillText(PoliscoreDatasetIF dataset);
	
}
