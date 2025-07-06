package us.poliscore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.service.storage.MemoryObjectStore;

@Data
@RequiredArgsConstructor
@EqualsAndHashCode(callSuper=false)
public class PoliscoreDataset extends MemoryObjectStore {
	
	@Data
	@RequiredArgsConstructor
	public static class DatasetReference {
		@NonNull
		protected LegislativeNamespace namespace;
		
		@NonNull
		protected Integer year;
	}
	
	@NonNull
	protected LegislativeSession session;
	
}
