package us.poliscore.entrypoint;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.tartarus.snowball.SnowballStemmer;
import org.tartarus.snowball.ext.englishStemmer;

import com.opencsv.CSVReader;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.PoliscoreConfigService;
import us.poliscore.service.storage.LocalCachedS3Service;

/**
 * Generates static resources for consumption by the webapp project 
 */
@QuarkusMain(name="WebappDataGenerator")
public class WebappDataGenerator implements QuarkusApplication
{
	
	protected static String WEBAPP_PATH = "../../webapp";
	
//	@Inject
//	private MemoryObjectService memService;
	
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	private GovernmentDataService data;
	
	@Inject private PoliscoreConfigService config;
	
	public static final String[] states = new String[] {
		"KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "MP", "AL", "AK", "AZ", "AR", "AS", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "GU", "HI", "ID", "IL", "IN", "IA", "KS", "OH", "OK", "OR", "PA", "PR", "RI", "SC", "SD", "TN", "TX", "TT", "UT", "VT", "VA", "VI", "WA", "WV", "WI", "WY" 
	};
	
	public static void main(String[] args) {
		Quarkus.run(WebappDataGenerator.class, args);
		Quarkus.asyncExit(0);
	}
	
	public void process() throws IOException
	{
		List<PoliscoreDataset> datasets = data.importDatasets();
		
		s3.optimizeExists(BillInterpretation.class);
		s3.optimizeExists(LegislatorInterpretation.class);
		
		generateRoutes();
		generateSiteMap(datasets);
		generateLegislatorWebappIndex(datasets);
		generateBillWebappIndex(datasets);
		writeSessionInfo(datasets);
		
		Log.info("Webapp Data Generator complete.");
	}
	
	@SneakyThrows
	private void writeSessionInfo(List<PoliscoreDataset> datasets) {
		final File out = new File(Environment.getDeployedPath(), WEBAPP_PATH + "/src/main/resources/sessions.json");
		val result = new ArrayList<LegislativeSession>();
		
		for (var dataset : datasets) {
			result.add(dataset.getSession());
		}
		
		FileUtils.write(out, PoliscoreUtil.getObjectMapper().writeValueAsString(result), "UTF-8");
	}
	
	@SneakyThrows
	private void generateRoutes() {
		final File out = new File(Environment.getDeployedPath(), WEBAPP_PATH + "/src/main/webui/routes.txt");
		val routes = new ArrayList<String>();
		val dataset = data.getDataset();
		
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
	
	@SneakyThrows
	private void generateSiteMap(List<PoliscoreDataset> datasets) {
		final String url = "https://poliscore.us";
		final File out = new File(Environment.getDeployedPath(), WEBAPP_PATH + "/src/main/webui/src/assets/sitemap.txt");
		val routes = new ArrayList<String>();
		
//		routes.add(url + "/about");
		
		for (var dataset : datasets)
		{
//			int lastYearOfSession = 1789 + (congress.getNumber() * 2) - 1;
			int lastYearOfSession = dataset.getSession().getEndDate().getYear();
			if (dataset.getSession().getNamespace().equals(LegislativeNamespace.US_CONGRESS))
				lastYearOfSession = lastYearOfSession - 1;
			
			String prefix = "/" + lastYearOfSession;
			String state = dataset.getSession().getNamespace().toAbbreviation().toLowerCase().replace("us", "congress");
			
			// Party Stats
			routes.add(url + prefix + "/" + state + "/democrat");
			routes.add(url + prefix + "/" + state + "/republican");
			routes.add(url + prefix + "/" + state + "/independent");
			
			// All states
//			Arrays.asList(states).stream().forEach(s -> routes.add(url + prefix + "/legislators/state/" + s.toLowerCase()));
			
			// All legislator routes
			routes.add(url + prefix + "/legislators");
			dataset.query(Legislator.class).stream()
				.filter(l -> l.isMemberOfSession(dataset.getSession())) //  && s3.exists(LegislatorInterpretation.generateId(l.getId(), congress.getNumber()), LegislatorInterpretation.class)
				.sorted((a,b) -> a.getDate().compareTo(b.getDate()))
				.forEach(l -> routes.add(url + prefix + "/legislator/" + l.getCode()));
			
			if (dataset.getSession().getCode().equals("118")) {
				// All bills
				routes.add(url + prefix + "/bills");
				dataset.query(Bill.class).stream()
					.filter(b -> s3.exists(BillInterpretation.generateId(b.getId(), null).replace("-polisc", ""), BillInterpretation.class))
					.sorted((a,b) -> a.getDate().compareTo(b.getDate()))
					.forEach(b -> routes.add(url + prefix + "/bill/" + b.getType().toLowerCase() + "/" + b.getNumber()));
			} else {
				// All bills
				routes.add(url + prefix + "/bills");
				dataset.query(Bill.class).stream()
					.filter(b -> s3.exists(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class))
					.sorted((a,b) -> a.getDate().compareTo(b.getDate()))
					.forEach(b -> routes.add(url + prefix + "/bill/" + b.getType().toLowerCase() + "/" + b.getNumber()));
			}
		}
		
		FileUtils.write(out, String.join("\n", routes), "UTF-8");
	}
	
	@SneakyThrows
	public void generateLegislatorWebappIndex(List<PoliscoreDataset> datasets) {
	    final File out = new File(Environment.getDeployedPath(), WEBAPP_PATH + "/src/main/resources/legislators.index");
	    Map<String, Set<String>> canonToNick = loadNicknameMap(); // canonical → nicknames

	    val uniqueSet = new HashMap<String, Legislator>();
	    List<List<String>> result = new ArrayList<List<String>>();

	    for (var dataset : datasets) {
		    dataset.query(Legislator.class).stream()
//		        .filter(l -> PoliscoreUtil.SUPPORTED_CONGRESSES.stream().anyMatch(s -> l.isMemberOfSession(s)))
		        .forEach(l -> {
		            if (!uniqueSet.containsKey(l.getCode())) {
		                uniqueSet.put(l.getCode(), l);
		            }
		        });
	
		    result.addAll(uniqueSet.values().stream().map(l -> {
		        String fullName = l.getName().getOfficial_full();
		        String canonFirst = fullName.split("\\s+")[0].toLowerCase();
		        String aliasTokens = fullName.toLowerCase();
	
		        Set<String> nicknames = canonToNick.getOrDefault(canonFirst, Set.of());
		        for (String nick : nicknames) {
		            aliasTokens += " " + nick;
		        }
	
		        return Arrays.asList(l.getId(), fullName, aliasTokens);
		    }).sorted(Comparator.comparing(a -> a.get(1))).toList());
	    }

	    FileUtils.write(out, PoliscoreUtil.getObjectMapper().writeValueAsString(result), "UTF-8");
	}
	
	// names.csv fetched from : https://raw.githubusercontent.com/carltonnorthern/nicknames/refs/heads/master/names.csv
	@SneakyThrows
	private Map<String, Set<String>> loadNicknameMap() {
	    Map<String, Set<String>> map = new HashMap<>();

	    try (CSVReader reader = new CSVReader(new InputStreamReader(getClass().getResourceAsStream("/names.csv")))) {
	        String[] row;
	        while ((row = reader.readNext()) != null) {
	            if (row.length < 2) continue;
	            String canonical = row[0].toLowerCase();

	            Set<String> nicknames = map.computeIfAbsent(canonical, k -> new HashSet<>());
	            for (int i = 1; i < row.length; i++) {
	                nicknames.add(row[i].toLowerCase());
	            }
	        }
	    }

	    return map;
	}
	
	@SneakyThrows
	public void generateBillWebappIndex(List<PoliscoreDataset> datasets) {
	    final File out = new File(Environment.getDeployedPath(), WEBAPP_PATH + "/src/main/resources/bills.index");
	    DateTimeFormatter usFormat = DateTimeFormatter.ofPattern("MM/dd/yyyy");
	    SnowballStemmer stemmer = new englishStemmer();
	    
	    List<List<String>> result = new ArrayList<List<String>>();

	    for (var dataset : datasets) {
		    result.addAll(dataset.query(Bill.class).stream()
		        .filter(b -> //PoliscoreUtil.SUPPORTED_CONGRESSES.stream().anyMatch(s -> b.isIntroducedInSession(s)) &&
		                     s3.exists(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class))
		        .map(b -> {
		            b.setInterpretation(s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).orElseThrow());
	
		            String displayName = b.getName();
		            String normalizedTokens = normalize(displayName, stemmer);
	
		            String label = b.isIntroducedInSession(data.getSession())
		                    ? displayName + " (" + b.getType() + " " + b.getNumber() + ")"
		                    : displayName + " (" + b.getIntroducedDate().format(usFormat) + ")";
	
		            return Arrays.asList(b.getId(), label, normalizedTokens);
		        })
		        .sorted(Comparator.comparing(b -> b.get(1)))
		        .toList());
	    }

	    FileUtils.write(out, PoliscoreUtil.getObjectMapper().writeValueAsString(result), "UTF-8");
	    Log.info("Generated a bill 'index' of size " + result.size());
	}

	private static final Set<String> STOPWORDS = Set.of(
	    "the", "of", "to", "and", "a", "in", "for", "on", "at", "by", "with", "act"
	);

	private String normalize(String input, SnowballStemmer stemmer) {
	    return Arrays.stream(input.toLowerCase()
	            .replaceAll("[^a-z0-9\\s]", " ")   // remove punctuation
	            .replaceAll("\\s+", " ")           // collapse spaces
	            .trim()
	            .split(" "))
	        .filter(token -> !STOPWORDS.contains(token))
	        .map(token -> {
	            stemmer.setCurrent(token);
	            stemmer.stem();
	            return stemmer.getCurrent();
	        })
	        .collect(Collectors.joining(" "));
	}
	
	@Override
    public int run(String... args) throws Exception {
        process();
        
        Quarkus.waitForExit();
        return 0;
    }
}
