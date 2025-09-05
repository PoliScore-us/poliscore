package us.poliscore;

import java.util.NoSuchElementException;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.service.storage.MemoryObjectStore;
import us.poliscore.service.storage.S3PersistenceService;

@Data
@EqualsAndHashCode(callSuper=false)
public class PoliscoreDataset extends MemoryObjectStore implements PoliscoreDatasetIF {
	
	@Data
	@RequiredArgsConstructor
	@AllArgsConstructor
	public static class DeploymentConfig {
		@NonNull
		protected LegislativeNamespace namespace;
		
		@NonNull
		protected Integer year;
		
		protected float multiplier = 1.0f;
	}
	
	@NonNull
	protected LegislativeSession session;
	
	@NonNull
	protected DeploymentConfig config;
	
	public boolean hasIndependentPartyMembers() {
		return query(Legislator.class).stream().filter(l -> l.getParty().equals(Party.INDEPENDENT)).count() > 0;
	}

	public boolean containsSession(String sessionKey) {
		return session.getKey().equals(sessionKey);
	}
	
	public LegislativeSession getObjectSession(SessionPersistable p) {
		if (session.getCode().equals(p.getSessionCode())) {
			return session;
		} else {
			throw new NoSuchElementException(p.getSessionCode());
		}
	}
	
	@Override
	public String getCode() {
		return session.getCode();
	}
	
	@Override
	public String getKey() {
		return getNamespace() + "/" + getCode();
	}

	@Override
	public LegislativeNamespace getNamespace() {
		return config.getNamespace();
	}

	@Override
	public int getStartYear() {
		return session.getStartDate().getYear();
	}

	@Override
	public int getEndYear() {
		if (this.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			return session.getEndDate().getYear() - 1;
		else
			return session.getEndDate().getYear();
	}

	@Override
	public String getDescription() {
		return session.getDescription();
	}

	@Override
	public boolean isYearWithin(int year) {
		return this.getStartYear() <= year && this.getEndYear() >= year;
	}

	@Override
	public LegislativeSession getRegularSession() {
		if (session.isRegular())
			return session;
		else
			return null;
	}
	
	@Override
	public <T extends Persistable> void optimizeExists(S3PersistenceService s3, Class<T> clazz) {
		s3.optimizeExists(clazz, session.getKey());
	}
	
	@Override
	public <T extends Persistable> void clearExistsOptimize(S3PersistenceService s3, Class<T> clazz) {
		s3.clearExistsOptimize(clazz, session.getKey());
	}
	
}
