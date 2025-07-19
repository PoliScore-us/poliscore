package us.poliscore.entrypoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.jboss.resteasy.reactive.RestQuery;
import org.joda.time.LocalDate;
import org.tartarus.snowball.SnowballStemmer;
import org.tartarus.snowball.ext.englishStemmer;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.LegislatorBillLinker;
import us.poliscore.LegislatorPageData;
import us.poliscore.Page;
import us.poliscore.model.CongressionalSession;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillIssueStat;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislatorBillInteractionList;
import us.poliscore.model.legislator.LegislatorBillInteraction;
import us.poliscore.model.legislator.LegislatorIssueStat;
import us.poliscore.model.session.SessionInterpretation;
import us.poliscore.service.IpGeolocationService;
import us.poliscore.service.storage.DynamoDbPersistenceService;

@Path("")
@RequestScoped
public class Lambda {
	
	public static final String TRACKED_ISSUE_INDEX = "~ti~";

    @Inject
    DynamoDbPersistenceService ddb;
//    CachedDynamoDbService ddb;
    
    @Inject
    IpGeolocationService ipService;
    
    @Inject
    ObjectMapper mapper;
    
    private static List<List<String>> cachedAllLegs;
    
    private static List<LegislativeSession> cachedSessions;
    
    private static Map<String, List<Legislator>> cachedLegislators = new HashMap<String, List<Legislator>>();
    
    private static Map<String, List<Bill>> cachedBills = new HashMap<String, List<Bill>>();
    
    private static List<Bill> allBillsDump;
    
    private static List<List<String>> allBillsIndex;
    
    @GET
    @Path("getSessionStats")
    public SessionInterpretation getSessionStats(@NonNull @RestQuery("namespace") String nsKey, @RestQuery int year) {
    	val namespace = LegislativeNamespace.of(nsKey);
    	val session = lookupSession(namespace, year);
    	
    	val op = ddb.get(SessionInterpretation.generateId(namespace, session.getCode()), SessionInterpretation.class);
    	
    	if (op.isEmpty()) {
    		for (var loopSes : getSessions()) {
    			if (!loopSes.equals(session) && loopSes.getNamespace().equals(session.getNamespace())) {
    				return ddb.get(SessionInterpretation.generateId(loopSes.getNamespace(), loopSes.getCode()), SessionInterpretation.class).orElse(null);
    			}
    		}
    	}
    	
    	return op.orElse(null);
    }
    
    @GET
    @Path("getLegislator")
    public Legislator getLegislator(@NonNull @RestQuery String id, @RestQuery("pageSize") Integer _pageSize, @RestQuery("index") String _index, @RestQuery("ascending") Boolean _ascending, @RestQuery("exclusiveStartKey") Integer _exclusiveStartKey, @RestQuery String sortKey) {
    	val index = StringUtils.isNotBlank(_index) ? _index : Persistable.OBJECT_BY_RATING_ABS_INDEX;
    	var pageSize = _pageSize == null ? 25 : _pageSize;
    	Boolean ascending = _ascending == null ? Boolean.FALSE : _ascending;
    	int exclusiveStartKey = (_exclusiveStartKey == null) ? -1 : _exclusiveStartKey;
    	
    	val op = ddb.get(id, Legislator.class);
    	
    	if (op.isPresent()) {
    		val leg = op.get();
    		
//    		if (_ascending == null && leg.getInterpretation().getRating() < 0)
//    			ascending = Boolean.TRUE;
    		
    		LegislatorBillLinker.linkInterpBills(leg, getSessions());
    		
    		var page = filterInteractions(leg, index, sortKey, pageSize, ascending, exclusiveStartKey);
    		
    		leg.setInteractions(page.getData().get(0));
    	}
    	
    	return op.orElse(null);
    }
    
    @GET
    @Path("/getLegislatorInteractions")
    public Page<LegislatorBillInteractionList> getLegislatorInteractions(@RestQuery("id") String id, @RestQuery("pageSize") Integer _pageSize, @RestQuery("index") String _index, @RestQuery("ascending") Boolean _ascending, @RestQuery("exclusiveStartKey") Integer _exclusiveStartKey, @RestQuery String sortKey) {
    	val index = StringUtils.isNotBlank(_index) ? _index : Persistable.OBJECT_BY_RATING_ABS_INDEX;
    	var pageSize = _pageSize == null ? 25 : _pageSize;
    	Boolean ascending = _ascending == null ? Boolean.FALSE : _ascending;
    	int exclusiveStartKey = (_exclusiveStartKey == null) ? -1 : _exclusiveStartKey;

    	val op = ddb.get(id, Legislator.class);
    	
    	if (op.isPresent()) {
    		val leg = op.get();
    		
    		return filterInteractions(leg, index, sortKey, pageSize, ascending, exclusiveStartKey);
    	}
    	
    	Page<LegislatorBillInteractionList> page = new Page<LegislatorBillInteractionList>();
    	page.setData(Arrays.asList());
    	page.setExclusiveStartKey(exclusiveStartKey);
    	page.setHasMoreData(false);
    	return page;
    }
    
    private Page<LegislatorBillInteractionList> filterInteractions(Legislator leg, String index, String sortKey, Integer pageSize, Boolean ascending, Integer exclusiveStartKey)
    {
    	Page<LegislatorBillInteractionList> page = new Page<LegislatorBillInteractionList>();
    	page.setData(Arrays.asList());
    	page.setExclusiveStartKey(exclusiveStartKey);
    	
    	val interacts = new LegislatorBillInteractionList();
    	page.setData(Arrays.asList(interacts));
    	
    	var stream = leg.getInteractions().stream();
		
		Comparator<LegislatorBillInteraction> comparator;
		if (index.equals(Persistable.OBJECT_BY_DATE_INDEX)) {
			comparator = Comparator.comparing(LegislatorBillInteraction::getDate);
		} else if (index.equals(Persistable.OBJECT_BY_RATING_INDEX)) {
			comparator = Comparator.comparing(LegislatorBillInteraction::getRating);
		} else if (index.equals(Persistable.OBJECT_BY_RATING_ABS_INDEX)) {
			comparator = Comparator.comparing(LegislatorBillInteraction::getRatingAbs);
		} else if (index.equals(Persistable.OBJECT_BY_IMPACT_INDEX)) {
			comparator = Comparator.comparing(LegislatorBillInteraction::getImpact);
		} else if (index.equals(Persistable.OBJECT_BY_IMPACT_ABS_INDEX)) {
			comparator = Comparator.comparing(LegislatorBillInteraction::getImpactAbs);
		} else if (index.equals(Persistable.OBJECT_BY_HOT_INDEX)) {
			comparator = Comparator.comparing(LegislatorBillInteraction::getHot);
		} else if (index.equals("TrackedIssue")) {
			var issue = TrackedIssue.valueOf(sortKey);
			stream = stream.filter(lbi -> lbi.getIssueStats().hasStat(issue));
			comparator = (LegislatorBillInteraction a, LegislatorBillInteraction b) -> Integer.valueOf(a.getRating(issue)).compareTo(b.getRating(issue));
		} else {
			throw new UnsupportedOperationException(index);
		}
		
		if (ascending) {
			stream = stream.sorted(comparator);
		} else {
			stream = stream.sorted(comparator.reversed());
		}
		
		var allInteracts = stream.collect(Collectors.toList());
		
		for (int i = exclusiveStartKey + 1; i < Math.min(allInteracts.size(), exclusiveStartKey + 1 + pageSize); ++i) {
			interacts.add(allInteracts.get(i));
		}
		
		page.setHasMoreData((interacts.size() + 1 + exclusiveStartKey) < allInteracts.size());
		
		return page;
    }
    
    @GET
    @Path("/getLegislators")
    public List<Persistable> getLegislators(@RestQuery("pageSize") Integer _pageSize, @RestQuery("index") String _index, @RestQuery("ascending") Boolean _ascending, @RestQuery("exclusiveStartKey") String _exclusiveStartKey, @RestQuery String sortKey, @RestQuery("year") Integer _year, @RestQuery("namespace") String _namespace) {
    	val index = StringUtils.isNotBlank(_index) ? _index : Persistable.OBJECT_BY_DATE_INDEX;
    	val startKey = _exclusiveStartKey;
    	var pageSize = _pageSize == null ? 25 : _pageSize;
    	Boolean ascending = _ascending == null ? Boolean.TRUE : _ascending;
    	
    	Integer year = _year == null ? LocalDate.now().getYear() : _year;
    	LegislativeNamespace namespace = StringUtils.isEmpty(_namespace) ? LegislativeNamespace.US_CONGRESS : LegislativeNamespace.of(_namespace);
    	val session = lookupSession(namespace, year);
    	String storageBucket = Persistable.getClassStorageBucket(Legislator.class, namespace, session.getCode());
    	
    	val cacheable = StringUtils.isBlank(startKey) && pageSize == 25 && !index.equals(Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX) && !index.equals(Persistable.OBJECT_BY_ISSUE_RATING_INDEX);
    	val cacheKey = storageBucket + "-" + index + "-" + ascending.toString() + (StringUtils.isBlank(sortKey) ? "" : "-" + sortKey);
    	if (cacheable && cachedLegislators.containsKey(cacheKey)) return cachedLegislators.get(cacheKey).stream().map(l -> (Persistable) l).toList();
    	
    	if (index.equals(Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX) || index.equals(Persistable.OBJECT_BY_ISSUE_RATING_INDEX)) {
    		storageBucket = LegislatorIssueStat.getIndexPrimaryKey(session.getNamespace(), session.getCode(), TrackedIssue.valueOf(sortKey));
    		sortKey = null;
    		val legs = ddb.query(LegislatorIssueStat.class, pageSize, index, ascending, startKey, sortKey, storageBucket);
    		return legs.stream().map(l -> (Persistable) l).toList();
    	}
    	
    	val legs = ddb.query(Legislator.class, session.getKey(), pageSize, index, ascending, startKey, sortKey);
    	
    	legs.forEach(l -> l.setInteractions(new LegislatorBillInteractionList()));
    	
    	if (cacheable) {
    		cachedLegislators.put(cacheKey, legs);
    	}
    	
    	return legs.stream().map(l -> (Persistable) l).toList();
    }
    
    @GET
    @SneakyThrows
    @Path("/getLegislatorPageData")
    public LegislatorPageData getLegislatorPageData(@Context APIGatewayV2HTTPEvent event, @RestQuery("state") String state, @RestQuery("year") Integer _year, @RestQuery("namespace") String _namespace) {
    	String location = null;
    	Integer year = _year == null ? LocalDate.now().getYear() : _year;
    	LegislativeNamespace namespace = StringUtils.isEmpty(_namespace) ? LegislativeNamespace.US_CONGRESS : LegislativeNamespace.of(_namespace);
    	
    	if (StringUtils.isNotBlank(state)) {
    		location = state.toUpperCase();
    	} else {
	    	try {
		    	val sourceIp = event.getRequestContext().getHttp().getSourceIp();
		    	location = ipService.locateIp(sourceIp).orElse(null);
	    	}
	    	catch(Exception e) {
	    		Log.error(e);
	    	}
    	}
    	
    	String index = (location == null ? null : Persistable.OBJECT_BY_LOCATION_INDEX);
    	
    	val legs = getLegislators(null, index, null, null, location, year, namespace.getNamespace());
    	
    	return new LegislatorPageData(location, legs, getAllLegs());
    }
    
    @SuppressWarnings("unchecked")
    @SneakyThrows
    private List<LegislativeSession> getSessions() {
    	if (cachedSessions == null) {
    		cachedSessions = mapper.readValue(IOUtils.toString(Lambda.class.getResourceAsStream("/sessions.json"), "UTF-8"), new TypeReference<List<LegislativeSession>>() {});
    	}
    	
    	return cachedSessions;
    }
    
    private LegislativeSession lookupSession(LegislativeNamespace namespace, int year) {
    	return getSessions().stream().filter(s -> s.getNamespace().equals(namespace) && s.isYearWithin(year)).findAny().get();
    }
    
    @SuppressWarnings("unchecked")
	@SneakyThrows
    private List<List<String>> getAllLegs() {
    	if (cachedAllLegs == null) {
    		cachedAllLegs = mapper.readValue(IOUtils.toString(Lambda.class.getResourceAsStream("/legislators.index"), "UTF-8"), List.class);
    	}
    	
    	return cachedAllLegs;
    }
    
    @GET
    @Path("/getBill")
    public Bill getBill(@NonNull @RestQuery("id") String id)
    {
    	val b = ddb.get(id, Bill.class).orElse(null);
    	
    	if (b != null && b.getInterpretation() != null) {
    		// Unfortunately the SliceInterpretations's "start" and "end" fields (e.g. /bill[1]/legis-body[1]/title[3]/section[15]) are confusing Google.
    		// Google thinks that it's a URL and is trying to follow it. Because we don't really use this data anyway (at the moment), we're going to just null it out for now.
    		b.getInterpretation().setSliceInterpretations(new ArrayList<BillInterpretation>());
    	}
    	
    	return b;
    }
    
    @GET
    @Path("/getBills")
    @SneakyThrows
    public List<Persistable> getBills(@RestQuery("pageSize") Integer _pageSize, @RestQuery("index") String _index, @RestQuery("ascending") Boolean _ascending, @RestQuery("exclusiveStartKey") String _exclusiveStartKey, @RestQuery String sortKey, @RestQuery("year") Integer _year, @RestQuery("namespace") String _namespace) {
    	val index = StringUtils.isNotBlank(_index) ? _index : Persistable.OBJECT_BY_DATE_INDEX;
    	val startKey = _exclusiveStartKey;
    	var pageSize = _pageSize == null ? 25 : _pageSize;
    	Boolean ascending = _ascending == null ? Boolean.TRUE : _ascending;
    	
    	Integer year = _year == null ? LocalDate.now().getYear() : _year;
    	LegislativeNamespace namespace = StringUtils.isEmpty(_namespace) ? LegislativeNamespace.US_CONGRESS : LegislativeNamespace.of(_namespace);
    	val session = lookupSession(namespace, year);
    	String storageBucket = Persistable.getClassStorageBucket(Bill.class, namespace, session.getCode());
    	
    	val cacheable = StringUtils.isBlank(startKey) && pageSize == 25 && StringUtils.isBlank(sortKey) && !index.startsWith(TRACKED_ISSUE_INDEX) && !index.equals(Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX) && !index.equals(Persistable.OBJECT_BY_ISSUE_RATING_INDEX);
    	val cacheKey = storageBucket + "-" + index + "-" + ascending.toString();
    	if (cacheable && cachedBills.containsKey(cacheKey)) return cachedBills.get(cacheKey).stream().map(l -> (Persistable) l).toList();
    	
    	List<Bill> bills;
    	if (index.equals(Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX) || index.equals(Persistable.OBJECT_BY_ISSUE_RATING_INDEX)) {
    		storageBucket = BillIssueStat.getIndexPrimaryKey(namespace, session.getCode(), TrackedIssue.valueOf(sortKey));
    		sortKey = null;
    		val bii = ddb.query(BillIssueStat.class, pageSize, index, ascending, startKey, sortKey, storageBucket);
    		return bii.stream().map(l -> (Persistable) l).toList();
    	} else {
    		bills = ddb.query(Bill.class, session.getKey(), pageSize, index, ascending, startKey, sortKey);
    	}
    	
    	if (cacheable) {
    		cachedBills.put(cacheKey, bills);
    	}
    	
    	return bills.stream().map(l -> (Persistable) l).toList();
    }
    
    @SuppressWarnings("unchecked")
    @SneakyThrows
    public List<Bill> getBillsDump() {
    	if (allBillsDump == null) {
    		allBillsDump = mapper.readValue(IOUtils.toString(Lambda.class.getResourceAsStream("/allbills.dump"), "UTF-8"), new TypeReference<List<Bill>>() {});
    	}
    	
    	return allBillsDump;
    }
    
    @SuppressWarnings("unchecked")
    @SneakyThrows
    public List<List<String>> getBillsIndex() {
    	if (allBillsIndex == null) {
    		allBillsIndex = mapper.readValue(IOUtils.toString(Lambda.class.getResourceAsStream("/bills.index"), "UTF-8"), List.class);
    	}
    	
    	return allBillsIndex;
    }
    
    @GET
    @Path("/queryBills")
    public List<List<String>> queryBills(@RestQuery("text") String text) {
    	SnowballStemmer stemmer = new englishStemmer();

        // Normalize query to tokens
        List<String> queryTokens = Arrays.stream(text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .split(" "))
            .filter(token -> !STOPWORDS.contains(token))
            .map(token -> {
                stemmer.setCurrent(token);
                stemmer.stem();
                return stemmer.getCurrent();
            })
            .toList();

        // Also check if query looks like a bill type + number
        String normalizedBillRef = normalizeBillRef(text);  // e.g., "hr123"

        List<List<String>> bills = new ArrayList<>(getBillsIndex());

        return bills.stream()
            .filter(bill -> {
                String billId = bill.get(0);       // us/congress/117/hr/123
                String stemmedTokens = bill.get(2);

                boolean matchesTokens = queryTokens.stream().allMatch(stemmedTokens::contains);
                boolean matchesTypeNumber = billId.replaceAll("[^a-z0-9]", "").endsWith(normalizedBillRef);

                return matchesTokens || matchesTypeNumber;
            })
            .sorted(Comparator.comparingInt(bill ->
                LevenshteinDistance.getDefaultInstance().apply(bill.get(1).toLowerCase(), text.toLowerCase())
            ))
            .limit(30)
            .map(bill -> List.of(bill.get(0), bill.get(1)))
            .collect(Collectors.toList());
    }

    private String normalizeBillRef(String input) {
        return input.toLowerCase().replaceAll("[^a-z0-9]", "");  // "H.R. 123" → "hr123"
    }


    private static final Set<String> STOPWORDS = Set.of(
        "the", "of", "to", "and", "a", "in", "for", "on", "at", "by", "with", "act"
    );

}
