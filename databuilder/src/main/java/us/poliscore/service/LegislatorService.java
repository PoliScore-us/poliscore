package us.poliscore.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislatorLegislativeTermSortedSet;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.legislator.LegislatorIssueStat;
import us.poliscore.service.storage.DynamoDbPersistenceService;
import us.poliscore.view.USCLegislatorView;

@ApplicationScoped
public class LegislatorService {
	
	@Inject
	private MemoryObjectService memService;
	
	@Inject
	private DynamoDbPersistenceService ddb;
	
	@Inject
	private LegislatorInterpretationService legInterp;
	
	public Optional<Legislator> getById(String id)
	{
		return memService.get(id, Legislator.class);
	}
	
	public void ddbPersist(Legislator leg, LegislatorInterpretation interp)
	{
		leg.setInterpretation(interp);
		ddb.put(leg);
		
		if (legInterp.meetsInterpretationPrereqs(leg))
		{
			for(TrackedIssue issue : TrackedIssue.values()) {
				ddb.put(new LegislatorIssueStat(issue, leg.getImpact(issue), leg));
			}
		}
	}

	@SneakyThrows
	public void generateLegislatorWebappIndex() {
	    final File out = new File(Environment.getDeployedPath(), "../../webapp/src/main/resources/legislators.index");
	    Map<String, Set<String>> canonToNick = loadNicknameMap(); // canonical → nicknames

	    val uniqueSet = new HashMap<String, Legislator>();

	    memService.queryAll(Legislator.class).stream()
	        .filter(l -> PoliscoreUtil.SUPPORTED_CONGRESSES.stream().anyMatch(s -> l.isMemberOfSession(s)))
	        .forEach(l -> {
	            if (!uniqueSet.containsKey(l.getBioguideId()) ||
	                uniqueSet.get(l.getBioguideId()).getSession() < l.getSession()) {
	                uniqueSet.put(l.getBioguideId(), l);
	            }
	        });

	    List<List<String>> data = uniqueSet.values().stream().map(l -> {
	        String fullName = l.getName().getOfficial_full();
	        String canonFirst = fullName.split("\\s+")[0].toLowerCase();
	        String aliasTokens = fullName.toLowerCase();

	        Set<String> nicknames = canonToNick.getOrDefault(canonFirst, Set.of());
	        for (String nick : nicknames) {
	            aliasTokens += " " + nick;
	        }

	        return Arrays.asList(l.getId(), fullName, aliasTokens);
	    }).sorted(Comparator.comparing(a -> a.get(1))).toList();

	    FileUtils.write(out, PoliscoreUtil.getObjectMapper().writeValueAsString(data), "UTF-8");
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

}
