package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.poliscore.bill.InterpretationRequest;

class PartyInterpretationServiceStateTest {

	@Test
	void clearsRequestsAndReturnsSnapshotForEachRun() throws Exception {
		var service = new PartyInterpretationService();
		var requests = requests(service);
		requests.add(null);

		var result = service.interpret(List.of());

		assertTrue(requests.isEmpty());
		assertNotSame(requests, result);
		result.add(null);
		assertTrue(requests.isEmpty());
	}

	@SuppressWarnings("unchecked")
	private List<InterpretationRequest> requests(PartyInterpretationService service) throws Exception {
		Field field = PartyInterpretationService.class.getDeclaredField("requests");
		field.setAccessible(true);
		return (List<InterpretationRequest>) field.get(service);
	}
}
