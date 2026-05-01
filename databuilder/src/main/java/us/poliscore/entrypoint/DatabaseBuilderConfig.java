package us.poliscore.entrypoint;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.Data;

/**
 * Defines global defaults for builder config
 */
@ApplicationScoped
@Data
public class DatabaseBuilderConfig {

	// What do we want to interpret?
	private boolean interpretNewBills = false;
	private boolean reinterpretLegislators = false;
	private boolean reinterpretParties = false;
	
	// ai knobs
	private boolean flexRequests = true;
	private boolean agenticWebSearch = true;
	
	// Not really used anymore
	private boolean interpretPressBills = false;

}
