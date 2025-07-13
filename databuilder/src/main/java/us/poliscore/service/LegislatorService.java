package us.poliscore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.legislator.LegislatorIssueStat;
import us.poliscore.service.storage.DynamoDbPersistenceService;

@ApplicationScoped
public class LegislatorService {
	
	@Inject
	private DynamoDbPersistenceService ddb;
	
	@Inject
	private LegislatorInterpretationService legInterp;
	
	public void ddbPersist(Legislator leg, LegislatorInterpretation interp)
	{
		leg.setInterpretation(interp);
		ddb.put(leg);
		
		if (legInterp.meetsInterpretationPrereqs(leg))
		{
			for(TrackedIssue issue : TrackedIssue.values()) {
				ddb.put(new LegislatorIssueStat(issue, leg.getImpact(issue), leg));
			}
		}
	}

}
