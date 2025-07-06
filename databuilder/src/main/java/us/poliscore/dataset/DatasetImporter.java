package us.poliscore.dataset;

import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DatasetReference;

public interface DatasetImporter {
	public PoliscoreDataset importDataset(DatasetReference ref);
}
