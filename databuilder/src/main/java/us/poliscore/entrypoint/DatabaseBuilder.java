package us.poliscore.entrypoint;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreDataset;
import us.poliscore.entrypoint.batch.BatchBillRequestGenerator;
import us.poliscore.entrypoint.batch.BatchLegislatorRequestGenerator;
import us.poliscore.entrypoint.batch.BatchOpenAIResponseImporter;
import us.poliscore.entrypoint.batch.PressBillInterpretationRequestGenerator;
import us.poliscore.model.DoubleIssueStats;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislatorBillInteractionList;
import us.poliscore.model.legislator.LegislatorBillInteraction;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.press.PressInterpretation;
import us.poliscore.service.BillInterpretationService;
import us.poliscore.service.BillService;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorInterpretationService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.PartyInterpretationService;
import us.poliscore.service.storage.DynamoDbPersistenceService;
import us.poliscore.service.storage.LocalCachedS3Service;

/**
 * Run this to keep a deployed server up-to-date.
 */
@QuarkusMain(name="DatabaseBuilder")
public class DatabaseBuilder implements QuarkusApplication
{
	public static boolean INTERPRET_PRESS_BILLS = true;
	
	public static boolean INTERPRET_NEW_BILLS = true;
	
	public static boolean REINTERPRET_LEGISLATORS = true;
	
	public static boolean REINTERPRET_PARTIES = false;
	
	@Inject
	private BatchBillRequestGenerator billRequestGenerator;
	
	@Inject
	private BatchLegislatorRequestGenerator legislatorRequestGenerator;
	
	@Inject
	private PartyInterpretationService partyInterpreter;
	
	@Inject
	private WebappDataGenerator webappDataGenerator;
	
	@Inject
	private BatchOpenAIResponseImporter responseImporter;
	
	@Inject
	private DynamoDbPersistenceService ddb;
	
	@Inject
	private LocalCachedS3Service s3;
	
	@Inject
	private BillService billService;
	
	@Inject
	private BillInterpretationService billInterpreter;
	
	@Inject
	private LegislatorService legService;
	
	@Inject
	private GovernmentDataService data;
	
	@Inject
	protected OpenAIService openAi;
	
	@Inject
	protected PressBillInterpretationRequestGenerator pressBillInterpGenerator;
	
	@Inject
	private LegislatorInterpretationService legInterp;
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	protected void process() throws IOException
	{
		data.importAllDatasets();
		
		val buildDatasets = data.getBuildDatasets();
		
		for (val dataset : buildDatasets) {
			data.syncS3LegislatorImages(dataset);
			data.syncS3BillText(dataset);
			
			s3.optimizeExists(BillInterpretation.class, dataset.getSession().getKey());
			s3.optimizeExists(LegislatorInterpretation.class, dataset.getSession().getKey());
			
			syncDdbWithS3(dataset);
		}
		
		interpretBillPressArticles(buildDatasets);
		interpretBills(buildDatasets);
		pressBillInterpGenerator.recordLastPressQueries(); // We want to record that our press query is complete, but only after the bill has been updated and re-interpreted (otherwise we would need to query again if it fails halfway through)
		
		interpretLegislators(buildDatasets);
		interpretPartyStats(buildDatasets);
		
		webappDataGenerator.process();
		
		Log.info("Poliscore database build complete.");
	}
	
	@SneakyThrows
	private void syncDdbWithS3(PoliscoreDataset dataset)
	{
		Log.info("Making sure that our ddb database is up-to-date with what exists on s3.");
		
		long amount = 0;
		
		// This could be optimized by building an "index" for each ddb database
		for (Bill b : dataset.query(Bill.class).stream().filter(b -> billInterpreter.isInterpreted(b.getId())).collect(Collectors.toList())) {
			var dbill = ddb.get(b.getId(), Bill.class).orElse(null);
			
			if (dbill == null 
			    || !Objects.equals(dbill.getStatus(), b.getStatus()) 
			    || !Objects.equals(dbill.getLastActionDate(), b.getLastActionDate())
			    || !Objects.equals(dbill.getName(), b.getName())) {
			    
			    val interp = s3.get(BillInterpretation.generateId(b.getId(), null), BillInterpretation.class).get();
			    billService.ddbPersist(b, interp);
			    amount++;
			}

		}
		
		Log.info("Updated " + amount + " out of sync bills in ddb from s3");
		Log.info("Decaying hot values");
		
		// Decay first x hot values //
		for (Bill b : ddb.query(Bill.class, dataset.getSession().getKey(), 1000, Persistable.OBJECT_BY_HOT_INDEX, false, null, null))
		{
			ddb.put(b);
		}
		
		// Update bills whose press interpretations are out of date //
		// TODO : Sort by date and only grab the top x amount
		Log.info("Syncing press interpretations");
		Set<Bill> updated = new HashSet<Bill>();
		for (val pi : s3.query(PressInterpretation.class, dataset.getSession().getKey())) {
			if (pi.isNoInterp()) continue;
			
			if (pi.getId().contains("null") || pi.getBillId().contains("null")) {
				s3.delete(pi.getId(), PressInterpretation.class);
				continue;
			}
			
			var bill = ddb.get(pi.getBillId(), Bill.class).orElse(null);
			
			if (bill != null && bill.getInterpretation() != null && !updated.contains(bill)) {
				var interp = bill.getInterpretation();
				
				if (interp.getPressInterps() == null) interp.setPressInterps(new ArrayList<PressInterpretation>());
				
				if (!interp.getPressInterps().stream().filter(ddbpi -> !ddbpi.isNoInterp()).anyMatch(ddbpi -> ddbpi.getId().equals(pi.getId()))) {
					interp = s3.get(BillInterpretation.generateId(pi.getBillId(), null), BillInterpretation.class).get();
					billService.ddbPersist(bill, interp);
					updated.add(bill);
				}
			}
		}
		Log.info("Updated " + updated.size() + " bills whose press interpretations were out of date.");
	}
	
	@SneakyThrows
	private void interpretBillPressArticles(List<PoliscoreDataset> buildDatasets) {
		if (INTERPRET_PRESS_BILLS) {
			List<File> requests = pressBillInterpGenerator.process(buildDatasets);
			
			if (requests.size() > 0) {
				List<File> responses = openAi.processBatch(requests);
				
				for (File f : responses) {
					responseImporter.process(f);
				}
			}
		}
	}
	
	private void interpretBills(List<PoliscoreDataset> buildDatasets) { interpretBills(buildDatasets, false); }
	@SneakyThrows private void interpretBills(List<PoliscoreDataset> buildDatasets, boolean isRecursive) {
		if (INTERPRET_NEW_BILLS) {
			List<File> requests = billRequestGenerator.process(buildDatasets, !isRecursive);
			
			if (requests.size() > 0) {
				List<File> responses = openAi.processBatch(requests);
				
				for (File f : responses) {
					responseImporter.process(f);
				}
				
				if (!isRecursive)
					interpretBills(buildDatasets, true);
			}
		}
	}
	
	@SneakyThrows
	private void interpretLegislators(List<PoliscoreDataset> buildDatasets) {
		if (REINTERPRET_LEGISLATORS) {
			List<File> requests = legislatorRequestGenerator.process(buildDatasets);
		
			if (requests.size() > 0) {
				List<File> responses = openAi.processBatch(requests);
				
				for (File f : responses) {
					responseImporter.process(f);
				}
			}
		}
		
		for(val dataset : buildDatasets)
			recalculateLegislators(dataset);
	}
	
	/**
	 * Recalculates all legislator stats and bill interactions without actually re-interpreting their activity. Saves on AI interpretation costs while
	 * still allowing stats and interactions to remain up-to-date.
	 */
	private void recalculateLegislators(PoliscoreDataset dataset) {
		Log.info("Recalculating legislators for " + dataset.getSession().getDescription());
		
		List<String> legsWithoutInterp = new ArrayList<String>();
		List<String> legsWithoutSufficientInteractions = new ArrayList<String>();
		
		for (var leg : dataset.query(Legislator.class).stream()
//				.filter(l -> l.isMemberOfSession(data.getSession())) //  && s3.exists(LegislatorInterpretation.generateId(l.getId(), PoliscoreUtil.CURRENT_SESSION.getNumber()), LegislatorInterpretation.class)
				.collect(Collectors.toList()))
		{
			legInterp.updateInteractionsInterp(dataset, leg);
			
//			if (leg.getInteractions().size() < 100) {
//				legsWithoutSufficientInteractions.add(leg.getBioguideId());
//				continue;
//			}
			
			LegislatorInterpretation interp = new LegislatorInterpretation(dataset.getSession().getNamespace(), dataset.getSession().getCode(), leg.getCode(), OpenAIService.metadata(), null);
			val interpOp = s3.get(LegislatorInterpretation.generateId(dataset.getSession().getNamespace(), dataset.getSession().getCode(), leg.getCode()), LegislatorInterpretation.class);
			
			if (interpOp.isPresent()) { interp = interpOp.get(); }
			
			// If there exists an interp from a previous session, backfill the interactions until we get to 1000
			if (legInterp.getInteractionsForInterpretation(leg).size() < 1000) {
				// If an interpretation from this session doesn't exist, grab one from the previous session.
				var previousSession = data.getPreviousSession(dataset.getSession());
				
				if (previousSession != null) {
					val prevInterpOp = s3.get(LegislatorInterpretation.generateId(previousSession.getNamespace(), previousSession.getCode(), leg.getCode()), LegislatorInterpretation.class);
					
					if (prevInterpOp.isPresent()) {
						if (StringUtils.isBlank(interp.getShortExplain()))
							interp.setShortExplain(prevInterpOp.get().getShortExplain());
						if (StringUtils.isBlank(interp.getLongExplain()))
							interp.setLongExplain(prevInterpOp.get().getLongExplain());
						
						String prevLegId = Legislator.generateId(previousSession.getNamespace(), previousSession.getCode(), leg.getCode());
						
						val prevLeg = ddb.get(prevLegId, Legislator.class).orElseThrow();
						
						val prevInteracts = prevLeg.getInteractions().stream().sorted(Comparator.comparing(LegislatorBillInteraction::getDate).reversed()).iterator();
						while (leg.getInteractionsPrivate1().size() < 1000 && prevInteracts.hasNext()) {
							val n = prevInteracts.next();
							if (n.getIssueStats() != null)
								leg.getInteractionsPrivate1().add(n);
						}
					}
				}
			}
			
			interp.setHash(legInterp.calculateInterpHashCode(leg));
			
			DoubleIssueStats stats = legInterp.calculateAgregateInteractionStats(leg);
			interp.setIssueStats(stats.toIssueStats());
			
//			if (interp.getIssueStats() == null || !interp.getIssueStats().hasStat(TrackedIssue.OverallBenefitToSociety) || StringUtils.isBlank(interp.getLongExplain())) {
//				legsWithoutInterp.add(leg.getBioguideId());
//				continue;
//			}
			
			leg.setInteractions(legInterp.getInteractionsForInterpretation(leg).stream()
					.filter(i -> i.getIssueStats() != null)
					.sorted((a,b) -> a.getDate().compareTo(b.getDate())).collect(Collectors.toCollection(LegislatorBillInteractionList::new)));
			
			leg.setInterpretation(interp);
			
			legInterp.calculateImpact(leg);
			
			legService.ddbPersist(leg, interp);
		}
		
		if (legsWithoutInterp.size() > 0 || legsWithoutSufficientInteractions.size() > 0) {
			System.out.println("Legislators without interpretations:");
			System.out.println(String.join(", ", legsWithoutInterp));
			System.out.println("Legislators without sufficient interactions:");
			System.out.println(String.join(", ", legsWithoutSufficientInteractions));
			
			throw new RuntimeException(legsWithoutInterp.size() + " legislators without interpretations " +
					legsWithoutSufficientInteractions.size() + " legislators without sufficient interactions");
		}
	}
	
	@SneakyThrows
	private void interpretPartyStats(List<PoliscoreDataset> buildDatasets) {
		if (REINTERPRET_PARTIES) {
			List<File> requests = partyInterpreter.process(buildDatasets);
			
			if (requests.size() > 0) {
				List<File> responses = openAi.processBatch(requests);
				
				for (File f : responses) {
					responseImporter.process(f);
				}
			}
		}
//		else {
//			val sit = s3.get(SessionInterpretationOld.generateId(PoliscoreUtil.CURRENT_SESSION.getNumber()), SessionInterpretationOld.class).orElse(null);
//			if (sit != null) {
//				ddb.put(sit);
//			}
//			
//			val sit2 = s3.get(SessionInterpretationOld.generateId(PoliscoreUtil.CURRENT_SESSION.getNumber()-1), SessionInterpretationOld.class).orElse(null);
//			if (sit2 != null) {
////				var newSit = SessionInterpretationConverter.fromOld(sit2);
////				
////				s3.put(newSit);
////				ddb.put(newSit);
//				
//				ddb.put(sit2);
//			}
//		}
	}
	
//	public class SessionInterpretationConverter {
//
//	    public static SessionInterpretationNew fromOld(SessionInterpretationOld old) {
//	        if (old == null) return null;
//
//	        SessionInterpretationNew converted = new SessionInterpretationNew();
//	        converted.setSession(old.getSession());
//	        converted.setMetadata(old.getMetadata());
//
//	        // Copy party interpretations
//	        converted.setDemocrat(copyPartyInterp(old.getDemocrat()));
//	        converted.setRepublican(copyPartyInterp(old.getRepublican()));
//	        converted.setIndependent(copyPartyInterp(old.getIndependent()));
//
//	        return converted;
//	    }
//
//	    private static SessionInterpretationNew.PartyInterpretation copyPartyInterp(SessionInterpretationOld.PartyInterpretation oldInterp) {
//	        if (oldInterp == null) return null;
//
//	        return new SessionInterpretationNew.PartyInterpretation(
//	                oldInterp.getParty(),
//	                oldInterp.getStats(),
//	                oldInterp.getLongExplain(),
//	                new SessionInterpretationNew.PartyBillSet(oldInterp.getMostImportantBills()),
//	                new SessionInterpretationNew.PartyBillSet(oldInterp.getLeastImportantBills()),
//	                new SessionInterpretationNew.PartyBillSet(oldInterp.getBestBills()),
//	                new SessionInterpretationNew.PartyBillSet(oldInterp.getWorstBills()),
//	                new SessionInterpretationNew.PartyLegislatorSet(oldInterp.getBestLegislators()),
//	                new SessionInterpretationNew.PartyLegislatorSet(oldInterp.getWorstLegislators())
//	        );
//	    }
//	}
	
	@Override
    public int run(String... args) throws Exception {
		try {
	        process();
	        
	        Quarkus.waitForExit();
	        return 0;
		} catch (Throwable t) {
			t.printStackTrace();
			System.exit(1);
		}
		
		return 1;
    }
	
	public static void main(String[] args) {
		Quarkus.run(DatabaseBuilder.class, args);
		Quarkus.asyncExit(0);
	}
}
