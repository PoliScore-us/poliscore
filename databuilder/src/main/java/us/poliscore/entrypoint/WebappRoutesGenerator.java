package us.poliscore.entrypoint;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import org.apache.commons.io.FileUtils;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.PoliscoreDataset;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name="WebappDataGenerator")
public class WebappRoutesGenerator implements QuarkusApplication {
	
	@Inject private GovernmentDataService data;
	
	@Inject
	private LocalCachedS3Service s3;
	
	public void process(PoliscoreDataset dataset) throws IOException
	{
		generateRoutes(dataset);
	}
	
	@SneakyThrows
	private void generateRoutes(PoliscoreDataset dataset) {
		final File out = new File(Environment.getDeployedPath(), WebappDataGenerator.WEBAPP_PATH + "/src/main/webui/routes.txt");
		val routes = new ArrayList<String>();
		
		// Party Stats
		routes.add("/congress/democrat");
		routes.add("/congress/republican");
		routes.add("/congress/independent");
		
		// All states
//		Arrays.asList(states).stream().forEach(s -> routes.add("/legislators/state/" + s.toLowerCase()));
		
		// All legislator routes
		routes.add("/legislators");
		dataset.query(Legislator.class).stream()
//			.filter(l -> l.isMemberOfSession(PoliscoreUtil.CURRENT_SESSION)) // && s3.exists(LegislatorInterpretation.generateId(l.getId(), PoliscoreUtil.CURRENT_SESSION.getNumber()), LegislatorInterpretation.class)
			.sorted((a,b) -> a.getDate().compareTo(b.getDate()))
			.forEach(l -> routes.add("/legislator/" + l.getCode()));
		
		// All bills
		routes.add("/bills");
		dataset.query(Bill.class).stream()
			.filter(b -> // b.isIntroducedInSession(PoliscoreUtil.CURRENT_SESSION) &&
					s3.exists(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class))
			.sorted((a,b) -> a.getDate().compareTo(b.getDate()))
			.forEach(b -> routes.add("/bill/" + b.getType().toLowerCase() + "/" + b.getNumber()));
		
		FileUtils.write(out, String.join("\n", routes), "UTF-8");
	}
	
	public static void main(String[] args) {
		Quarkus.run(WebappDataGenerator.class, args);
		Quarkus.asyncExit(0);
	}
	
	@Override
	public int run(String... args) throws Exception {
	    if (args.length < 2) {
	        System.err.println("Usage: ./run.sh <namespace> <year>");
	        return 1;
	    }

	    String namespace = args[0];
	    int year = Integer.parseInt(args[1]);

	    data.importAllDatasets();
	    PoliscoreDataset dataset = data.getDataset(LegislativeNamespace.of(namespace), year);
	    
	    process(dataset);

	    Quarkus.waitForExit();
	    return 0;
	}

}
