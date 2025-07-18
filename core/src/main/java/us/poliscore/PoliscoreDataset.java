package us.poliscore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import us.poliscore.model.CongressionalSession;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.service.storage.MemoryObjectStore;

@Data
@EqualsAndHashCode(callSuper=false)
public class PoliscoreDataset extends MemoryObjectStore {
	
	@Data
	@RequiredArgsConstructor
	public static class DeploymentConfig {
		@NonNull
		protected LegislativeNamespace namespace;
		
		@NonNull
		protected Integer year;
	}
	
	@NonNull
	protected LegislativeSession session;
	
	public boolean hasIndependentPartyMembers() {
		return query(Legislator.class).stream().filter(l -> l.getParty().equals(Party.INDEPENDENT)).count() > 0;
	}

	
}
