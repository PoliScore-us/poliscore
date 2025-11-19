package us.poliscore.dataset;

import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;
import us.poliscore.model.bill.BillText;
import us.poliscore.service.storage.ObjectStorageServiceIF;
import us.poliscore.service.storage.S3PersistenceService;

public interface PoliscoreDatasetIF extends ObjectStorageServiceIF {
	
	public LegislativeSession getRegularSession();
	
	public LegislativeSession getObjectSession(SessionPersistable p);
	
	public String getCode();
	
	public String getKey();
	
	public LegislativeNamespace getNamespace();
	
	public int getStartYear();
	
	public int getEndYear();
	
	public String getDescription();
	
	public DeploymentConfig getConfig();
	
	public boolean containsSession(String sessionKey);
	
	public boolean hasIndependentPartyMembers();

	public boolean isYearWithin(int year);
	
	public boolean isCurrent();
	
	public <T extends Persistable> void optimizeExists(S3PersistenceService s3, Class<T> clazz);

	public <T extends Persistable> void clearExistsOptimize(S3PersistenceService s3, Class<T> class1);
	
}
