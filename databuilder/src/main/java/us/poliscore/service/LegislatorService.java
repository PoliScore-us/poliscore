package us.poliscore.service;

import java.util.Comparator;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.legislator.LegislatorIssueStat;
import us.poliscore.model.legislator.LegislatorMediaReference;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.ObjectStorageServiceIF;

@ApplicationScoped
public class LegislatorService {
	
	@Inject
	private LegislatorInterpretationService legInterp;

	@Inject
	private LocalCachedS3Service s3;

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
		leg.setMediaCoverage(getMediaReferences(leg));
	}

	public List<LegislatorMediaReference> getMediaReferences(Legislator legislator) {
		return getMediaReferences(legislator.getId());
	}

	public List<LegislatorMediaReference> getMediaReferences(String legislatorId) {
		String[] parts = legislatorId.split("/", 5);
		if (parts.length != 5 || !Legislator.ID_CLASS_PREFIX.equals(parts[0])) {
			throw new IllegalArgumentException("Not a Legislator id: " + legislatorId);
		}

		String sessionKey = parts[1] + "/" + parts[2] + "/" + parts[3];
		String objectKey = parts[4] + "-";
		return s3.query(LegislatorMediaReference.class, sessionKey, objectKey).stream()
				.filter(reference -> legislatorId.equals(reference.getLegislatorId()))
				.filter(reference -> !reference.isNoInterp())
				.sorted(Comparator.comparing(LegislatorMediaReference::getPublishedDate,
						Comparator.nullsLast(Comparator.reverseOrder()))
						.thenComparing(LegislatorMediaReference::getId))
				.toList();
	}

	public void persistMediaReferences(List<LegislatorMediaReference> references) {
		if (references == null) return;
		for (LegislatorMediaReference reference : references) {
			s3.put(reference);
		}
	}

}
