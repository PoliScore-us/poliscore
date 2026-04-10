package us.poliscore.entrypoint;


import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillIssueStat;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.MemoryObjectService;
import us.poliscore.service.SessionInfoService;
import us.poliscore.service.storage.CachedPostgresService;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.service.storage.LocalFilePersistenceService;

@QuarkusMain(name="DataSandbox")
public class DataSandbox implements QuarkusApplication
{
	private static final String TRACKED_ISSUE_INDEX = "~ti~";
	private static final Map<String, List<Bill>> cachedBills = new HashMap<String, List<Bill>>();

	@Inject
	private MemoryObjectService memService;
	
	@Inject
	private LocalFilePersistenceService localStore;
	
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	private LegislatorService legService;
	
	@Inject
	private BillService billService;
	
	@Inject
	private GovernmentDataService data;

	@Inject
	private CachedPostgresService ddb;

	@Inject
	private ObjectMapper mapper;
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	protected void process() throws IOException
	{
		data.importAllDatasets();
		
		// https://5hta4jxn7q6cfcyxnvz4qmkyli0tambn.lambda-url.us-east-1.on.aws//getBills?index=ObjectsByIssueRating&pageSize=25&ascending=true&sortKey=CrimeAndLawEnforcement&year=2025&namespace=us/az
		val out = getBills(25, Persistable.OBJECT_BY_ISSUE_RATING_INDEX, false, null, "CrimeAndLawEnforcement", 2025, LegislativeNamespace.US_ARIZONA.getNamespace());
		System.out.println("Fetched " + out.size() + " bills");
		System.out.println(mapper.writeValueAsString(out));
		
		
//		val sessionStats = new SessionInterpretation();
//		sessionStats.setSession(PoliscoreUtil.CURRENT_SESSION.getNumber());
//		val stats = s3.get(sessionStats.getId(), SessionInterpretation.class).get();
//		stats.getDemocrat().getStats().getStats().remove(TrackedIssue.SocialEquity);
//		stats.getRepublican().getStats().getStats().remove(TrackedIssue.SocialEquity);
//		stats.getIndependent().getStats().getStats().remove(TrackedIssue.SocialEquity);
//		
//		s3.put(sessionStats);
		
		
//		s3.optimizeExists(BillInterpretation.class);
//		s3.optimizeExists(BillText.class);
//		for(val bill : memService.query(Bill.class))
//		{
//			if (!s3.exists(bill.getId().replace(Bill.ID_CLASS_PREFIX, BillInterpretation.ID_CLASS_PREFIX), BillInterpretation.class)
//				|| !s3.exists(bill.getId().replace(Bill.ID_CLASS_PREFIX, BillText.ID_CLASS_PREFIX), BillText.class))
//				continue;
//			
//			val interp = s3.get(bill.getId().replace(Bill.ID_CLASS_PREFIX, BillInterpretation.ID_CLASS_PREFIX), BillInterpretation.class).get();
//			
//			for (var sliceInterp : interp.getSliceInterpretations())
//			{
//				sliceInterp.getIssueStats().getStats().remove(TrackedIssue.SocialEquity);
//				s3.put(sliceInterp);
//			}
//			
//			interp.getIssueStats().getStats().remove(TrackedIssue.SocialEquity);
//			
//			s3.put(interp);
//		}
		
		
//		for(val bill : memService.query(Bill.class))
//		{
//			val op = s3.get(bill.getId().replace(Bill.ID_CLASS_PREFIX, BillInterpretation.ID_CLASS_PREFIX), BillInterpretation.class);
//			
//			if (op.isPresent())
//			{
////				val old = op.get().getId();
//				
////				op.get().setId(LegislatorInterpretation.generateId(leg.getId(), PoliscoreUtil.CURRENT_SESSION.getNumber()));
//				op.get().getIssueStats().getStats().remove(TrackedIssue.SocialEquity);
//				
////				System.out.println(old + " migrated to " + op.get().getId());
//				s3.put(op.get());
////				System.out.println(PoliscoreUtil.getObjectMapper().writeValueAsString(op.get()));
//				
//				
////				op.get()
//			}
//		}
		
		
//		for(val leg : memService.query(Legislator.class))
//		{
//			val op = s3.get(leg.getId().replace(Legislator.ID_CLASS_PREFIX, LegislatorInterpretation.ID_CLASS_PREFIX), LegislatorInterpretation.class);
//			
//			if (op.isPresent())
//			{
////				val old = op.get().getId();
//				
////				op.get().setId(LegislatorInterpretation.generateId(leg.getId(), PoliscoreUtil.CURRENT_SESSION.getNumber()));
//				op.get().getIssueStats().getStats().remove(TrackedIssue.SocialEquity);
//				
////				System.out.println(old + " migrated to " + op.get().getId());
//				s3.put(op.get());
////				System.out.println(PoliscoreUtil.getObjectMapper().writeValueAsString(op.get()));
//				
//				
////				op.get()
//			}
//		}
		
		
		
		
		
		/*
		 * 
		 */
//		val op = ddb.get(SessionInterpretation.generateId(118), SessionInterpretation.class);
//		if (StringUtils.isBlank(op.get().getDemocrat().getLongExplain()))
//		{
//			System.out.println("Democrat is blank");
//		}
//		if (StringUtils.isBlank(op.get().getRepublican().getLongExplain()))
//		{
//			System.out.println("Republican is blank");
//		}
//		if (StringUtils.isBlank(op.get().getIndependent().getLongExplain()))
//		{
//			System.out.println("Independent is blank");
//		}
		
		
		
		
		
//		val obj = dynamoDb.get("BIL/us/congress/118/hr/4763", Bill.class).orElseThrow();
//		
//		System.out.println(PoliscoreUtil.getObjectMapper().valueToTree(obj));
		
		
		
//		val legs = dynamoDb.query(Legislator.class, 25, null, null, null);
//		System.out.println(legs.size());
//		System.out.println(PoliscoreUtil.getObjectMapper().valueToTree(legs));
		
		
//		val leg = dynamoDb.get(Legislator.generateId(LegislativeNamespace.US_CONGRESS, "F000480"), Legislator.class);
//		System.out.println(PoliscoreUtil.getObjectMapper().valueToTree(leg));
		
		
//		val out = getLegislatorPageData();
		
		
//		String sourceIp = "71.56.241.71";
//		val location = ipService.locateIp(sourceIp).orElse(null);
////		String location = "CO";
//    	val out = getLegislators(10, (location == null ? null : Persistable.OBJECT_BY_LOCATION_INDEX), true, "LEG/us/congress/C001134~`~CO/8", null);
    	
		
//		val date = "1980-12-23";
//		val out = getLegislators(null, Persistable.OBJECT_BY_DATE_INDEX, null, null, null);
		
//		val out = getBills(25, Persistable.OBJECT_BY_DATE_INDEX, false, null, null);
		
		
//		val out = queryBills("gun");
    	
//    	val out = getLegislatorInteractions(PoliscoreUtil.BERNIE_SANDERS_ID, 19);
		
//		val out = ddb.get(Legislator.generateId(LegislativeNamespace.US_CONGRESS, "K000402"), Legislator.class).orElseThrow();
		
//		val out = leg.getInteractions();
		
//		val out = leg.calculateTopInteractions();
		
//		linkInterpBills(leg);
		
//		val out = leg.getInterpretation().getIssueStats().getExplanation();
		
		
		
//		val out = memService.query(Bill.class).stream()
//			.filter(b -> b.getInterpretation() == null || b.getInterpretation().getIssueStats() == null || !b.getInterpretation().getIssueStats().hasStat(TrackedIssue.OverallBenefitToSociety))
//			.map(b -> b.getId())
//			.toList();		
		
		
//		val out = getBills(25, Lambda.TRACKED_ISSUE_INDEX + TrackedIssue.NationalDefense.name(), false, null, null);
		
//		s3.optimizeExists(BillInterpretation.class);
//		
//		val out = memService.query(Bill.class).stream()
//				.filter(b -> b.isIntroducedInSession(CongressionalSession.S118) && s3.exists(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class))
//				.toList().size();
//		
//		
//    	
//    	System.out.println(PoliscoreUtil.getObjectMapper().valueToTree(out));
//		System.out.println(out);
		
		
		System.out.println("Program Complete");
	}

	@SneakyThrows
	public List<Persistable> getBills(Integer _pageSize, String _index, Boolean _ascending, String _exclusiveStartKey,
			String sortKey, Integer _year, String _namespace) {
		val index = StringUtils.isNotBlank(_index) ? _index : Persistable.OBJECT_BY_DATE_INDEX;
		val startKey = _exclusiveStartKey;
		var pageSize = _pageSize == null ? 25 : _pageSize;
		Boolean ascending = _ascending == null ? Boolean.TRUE : _ascending;

		Integer year = _year == null ? java.time.LocalDate.now().getYear() : _year;
		LegislativeNamespace namespace = StringUtils.isEmpty(_namespace) ? LegislativeNamespace.US_CONGRESS
				: LegislativeNamespace.of(_namespace);
		val session = SessionInfoService.lookupRegularSession(namespace, year);

		String storageBucket;
		if (namespace.equals(LegislativeNamespace.US_CONGRESS))
			storageBucket = Persistable.getClassStorageBucket(Bill.class, namespace, session.getCode());
		else {
			storageBucket = Persistable.getIdClassPrefix(Bill.class) + "/" + namespace + "/"
					+ String.valueOf(SessionInfoService.lookupRegularSession(namespace, session.getCode()).getEndDate().getYear());
		}

		val cacheable = StringUtils.isBlank(startKey) && pageSize == 25 && StringUtils.isBlank(sortKey)
				&& !index.startsWith(TRACKED_ISSUE_INDEX)
				&& !index.equals(Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX)
				&& !index.equals(Persistable.OBJECT_BY_ISSUE_RATING_INDEX);
		val cacheKey = storageBucket + "-" + index + "-" + ascending.toString();
		if (cacheable && cachedBills.containsKey(cacheKey))
			return cachedBills.get(cacheKey).stream().map(l -> (Persistable) l).toList();

		List<Bill> bills;
		if (index.equals(Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX)
				|| index.equals(Persistable.OBJECT_BY_ISSUE_RATING_INDEX)) {
			storageBucket = storageBucket + "/" + TrackedIssue.valueOf(sortKey).name();
			sortKey = null;
			val bii = ddb.query(BillIssueStat.class, pageSize, index, ascending, startKey, sortKey, storageBucket);
			return bii.stream().map(l -> (Persistable) l).toList();
		} else {
			bills = ddb.query(Bill.class, pageSize, index, ascending, startKey, sortKey, storageBucket);
		}

		if (cacheable) {
			cachedBills.put(cacheKey, bills);
		}

		return bills.stream().map(l -> (Persistable) l).toList();
	}
	
	public static void main(String[] args) {
		Quarkus.run(DataSandbox.class, args);
		Quarkus.asyncExit(0);
	}
	
	@Override
    public int run(String... args) throws Exception {
        process();
        
        Quarkus.waitForExit();
        return 0;
    }
}
