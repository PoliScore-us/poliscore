package us.poliscore.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.legislator.LegislatorIssueStat;
import us.poliscore.service.storage.ObjectStorageServiceIF;

@ApplicationScoped
public class LegislatorService {
	
	@Inject
	private LegislatorInterpretationService legInterp;

	public void persist(Legislator leg, LegislatorInterpretation interp, ObjectStorageServiceIF store)
	{
		applyInterpretation(leg, interp);
		store.put(leg);

		if (legInterp.meetsInterpretationPrereqs(leg)) {
			for (TrackedIssue issue : TrackedIssue.values()) {
				store.put(new LegislatorIssueStat(issue, leg.getImpact(issue), leg));
			}
		}
	}

	public void applyInterpretation(Legislator leg, LegislatorInterpretation interp)
	{
		leg.setInterpretation(interp);
	}

}
