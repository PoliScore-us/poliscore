package us.poliscore.dataset;

import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.BuildReport;
import us.poliscore.model.bill.Bill;

public interface DatasetProvider {
	
	public PoliscoreDatasetIF importDataset(DeploymentConfig ref);

	public default PoliscoreDatasetIF importDataset(DeploymentConfig ref, BuildReport report) {
		return importDataset(ref);
	}
	
//	public PoliscoreDatasetIF getPreviousDataset(PoliscoreDatasetIF dataset);
	
//	public LegislativeSession getPreviousRegularSession(LegislativeSession current);
	
	public void syncS3LegislatorImages(PoliscoreDatasetIF dataset);
	
	public void syncS3BillText(PoliscoreDatasetIF dataset);
	
}
