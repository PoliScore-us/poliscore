package us.poliscore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import us.poliscore.model.CongressionalSession;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
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
	
	public boolean isDeployment(DeploymentConfig deployment) {
		if (!this.session.getNamespace().equals(deployment.getNamespace())) {
			return false;
		}
		if (deployment.getNamespace().equals(LegislativeNamespace.US_CONGRESS)) {
			// Normalize year to session number match
			var targetSession = CongressionalSession.fromYear(deployment.getYear());
			var thisSession = CongressionalSession.of(Integer.parseInt(this.session.getCode()));
			return thisSession.equals(targetSession);
		} else {
			// Fall back to year-based match for states
			return deployment.getYear().equals(this.session.getEndDate().getYear());
		}
	}

	
}
