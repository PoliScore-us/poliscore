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

}
