package us.poliscore.entrypoint;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.service.PartyInterpretationService;

@QuarkusMain(name="SessionStatsBuilder")
public class SessionStatsBuilder implements QuarkusApplication
{
	@Inject
	private PartyInterpretationService partyInterpreter;
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	public static void main(String[] args) {
		Quarkus.run(SessionStatsBuilder.class, args);
	}
	
	public void process() throws IOException
	{
		partyInterpreter.process();
		
		Log.info("Session stats build complete.");
	}
	
	@Override
    public int run(String... args) throws Exception {
        process();
        
        Quarkus.waitForExit();
        return 0;
    }
}
