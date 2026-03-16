package us.poliscore.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillTextFormat;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextPublishVersion;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.service.storage.S3PersistenceService.QueryCriteria;

class S3PersistenceServiceTest {

	@AfterEach
	void clearOptimizeExistsCache() throws Exception {
		getOptimizedObjectsCache().clear();
	}

	@Test
	void queryUsesOptimizeExistsKeysWhenAvailable() throws Exception {
		String sessionKey = "us/congress/119";
		String storageBucket = Persistable.getClassStorageBucket(BillText.class, sessionKey);
		
		BillText hr1 = billText(1, BillTextPublishVersion.ENR);
		BillText hr2 = billText(2, BillTextPublishVersion.IH);
		BillText hr10 = billText(10, BillTextPublishVersion.ENR);
		
		getOptimizedObjectsCache().put(storageBucket, new HashSet<>(Set.of(
				hr10.getId(),
				hr2.getId(),
				hr1.getId())));
		
		TestS3PersistenceService s3 = new TestS3PersistenceService(hr1, hr2, hr10);
		
		List<BillText> results = s3.query(BillText.class, sessionKey, new QueryCriteria("hr/", null, null, 2, true));
		
		assertEquals(List.of(hr1.getId(), hr10.getId()), results.stream().map(BillText::getId).toList());
	}

	@SuppressWarnings("unchecked")
	private HashMap<String, Set<String>> getOptimizedObjectsCache() throws Exception {
		Field field = S3PersistenceService.class.getDeclaredField("objectsInBucket");
		field.setAccessible(true);
		return (HashMap<String, Set<String>>) field.get(null);
	}
	
	private BillText billText(int billNumber, BillTextPublishVersion version) {
		String billId = Bill.generateId(LegislativeNamespace.US_CONGRESS, "119", CongressionalBillType.HR, billNumber);
		return BillText.factory(billId, "<bill>" + billNumber + "</bill>", LocalDate.of(2025, 1, 1), version, BillTextFormat.XML);
	}
	
	private static class TestS3PersistenceService extends S3PersistenceService {
		private final HashMap<String, BillText> stored = new HashMap<>();
		
		TestS3PersistenceService(BillText... texts) {
			for (BillText text : texts) {
				stored.put(text.getId(), text);
			}
		}
		
		@Override
		public <T extends us.poliscore.model.Persistable> Optional<T> get(String id, Class<T> clazz) {
			return Optional.ofNullable(clazz.cast(stored.get(id)));
		}
	}
}
