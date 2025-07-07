package us.poliscore.dataset;

import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DatasetReference;
import us.poliscore.model.LegislativeSession;

public interface DatasetSupplier {
	
	public PoliscoreDataset importDataset(DatasetReference ref);
	
	public LegislativeSession getPreviousSession(LegislativeSession current);
	
}
