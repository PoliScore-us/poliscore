package us.poliscore.entrypoint;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.Data;
import us.poliscore.legiscan.view.RefreshFrequency;

/**
 * Defines global defaults for builder config
 */
@ApplicationScoped
@Data
public class DatabaseBuilderConfig {
	
	// What do we want to interpret?
	private boolean interpretNewBills = true;
	private boolean refreshStaleBillAnalyses = false;
	private boolean reinterpretLegislators = false;
	private boolean includeRealizedSocietalImpact = false;
	private boolean reinterpretParties = false;
	
	private boolean includeReferendums = false;
	private boolean interpretReferendums = false;
	private boolean refreshStaleReferendumAnalyses = false;

	// ai knobs
	private boolean flexRequests = true;
	private boolean agenticWebSearch = true;
	
	// Not really used anymore
	private boolean interpretPressBills = false;

}
