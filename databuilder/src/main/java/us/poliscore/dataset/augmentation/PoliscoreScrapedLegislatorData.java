package us.poliscore.dataset.augmentation;

import java.time.LocalDate;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import us.poliscore.model.SessionPersistable;
import us.poliscore.model.legislator.Legislator;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@RegisterForReflection
public class PoliscoreScrapedLegislatorData extends SessionPersistable {
	
	public static final String ID_CLASS_PREFIX = "PLA";
	
	public static String generateId(String legId) { return legId.replace(Legislator.ID_CLASS_PREFIX, ID_CLASS_PREFIX); }
	
	protected String poliscoreId;
	
	protected String officialUrl;
	
	protected LocalDate birthday;
	
}
