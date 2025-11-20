package us.poliscore.entrypoint;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.io.FileUtils;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name="WebappRoutesGenerator")
public class WebappRoutesGenerator implements QuarkusApplication {
	
	@Inject private GovernmentDataService data;
	
	@Inject
	private LocalCachedS3Service s3;
	
	public void process(PoliscoreDatasetIF dataset) throws IOException
	{
		generateRoutes(dataset);
		
		Log.info("Successfully generated webapp routes for dataset " + dataset.getDescription());
	}
	
	@SneakyThrows
	private void generateRoutes(PoliscoreDatasetIF dataset) {
		final File out = new File(Environment.getDeployedPath(), WebappDataGenerator.WEBAPP_PATH + "/src/main/webui/routes.txt");
		val routes = new ArrayList<String>();
		
		// Hardcoded routes
		routes.add("/legal/terms");
		routes.add("/legal/privacy");
		routes.add("/auth-callback");
		routes.add("/billing/resume");
		routes.add("/billing/cancel");
		routes.add("/billing/success");
		
		// Party Stats
		routes.add("/party");
		routes.add("/party/democrat");
		routes.add("/party/republican");
		
		if (dataset.hasIndependentPartyMembers())
			routes.add("/party/independent");
		
		// All states
//		Arrays.asList(states).stream().forEach(s -> routes.add("/legislators/state/" + s.toLowerCase()));
		
		// We need to generate pages for EVERY legislator. This is mostly for SEO reasons and so we can always have an up-to-date page on a legislator
		Set<String> processedLegs = new HashSet<String>();
		for (var ds : data.getAllImportedDatasets()) { 
			routes.add("/legislators");
			ds.query(Legislator.class).stream()
	//			.filter(l -> l.isMemberOfSession(PoliscoreUtil.CURRENT_SESSION)) // && s3.exists(LegislatorInterpretation.generateId(l.getId(), PoliscoreUtil.CURRENT_SESSION.getNumber()), LegislatorInterpretation.class)
				.filter(l -> !processedLegs.contains(l.getId()))
				.sorted((a,b) -> a.getDate().compareTo(b.getDate()))
				.forEach(l -> { routes.add("/legislator/" + l.getCode()); processedLegs.add(l.getId()); });
		}
		
		// All bills
		routes.add("/bills");
		dataset.query(Bill.class).stream()
			.filter(b -> // b.isIntroducedInSession(PoliscoreUtil.CURRENT_SESSION) &&
					s3.exists(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class))
			.sorted((a,b) -> a.getDate().compareTo(b.getDate()))
			.forEach(b -> routes.add("/" + b.getWebappUrlPath()));
		
		FileUtils.write(out, String.join("\n", routes), "UTF-8");
	}
	
	public static void main(String[] args) {
		Quarkus.run(WebappRoutesGenerator.class, args);
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
	    PoliscoreDatasetIF dataset = data.getDataset(LegislativeNamespace.of(namespace), year);
	    
	    process(dataset);

	    Quarkus.waitForExit();
	    return 0;
	}

}
