package us.poliscore.model;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import us.poliscore.legiscan.view.LegiscanDatasetView;
import us.poliscore.legiscan.view.LegiscanState;

@Data
@AllArgsConstructor
public class LegislativeSession {
	
	protected LocalDate startDate;
	
	protected LocalDate endDate;
	
	protected String key;
	
	protected LegislativeNamespace namespace;
	
	public boolean isOver() {
		return LocalDate.now().isAfter(endDate);
	}
	
}
