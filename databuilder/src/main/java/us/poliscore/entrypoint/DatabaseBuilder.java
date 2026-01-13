package us.poliscore.entrypoint;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.Environment;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.entrypoint.batch.BatchBillRequestGenerator;
import us.poliscore.entrypoint.batch.BatchLegislatorRequestGenerator;
import us.poliscore.entrypoint.batch.BatchOpenAIResponseImporter;
import us.poliscore.entrypoint.batch.PressBillInterpretationRequestGenerator;
import us.poliscore.model.BuildReport;
import us.poliscore.model.DoubleIssueStats;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislatorBillInteractionList;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.LegislatorInterpretationService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.PartyInterpretationService;
import us.poliscore.service.SessionInfoService;
import us.poliscore.service.storage.LocalCachedS3Service;

/**
 * Run this to keep a deployed server up-to-date.
 */
@QuarkusMain(name="DatabaseBuilder")
public class DatabaseBuilder implements QuarkusApplication
{
	public static boolean INTERPRET_PRESS_BILLS = false;
	
	public static boolean INTERPRET_NEW_BILLS = true;
	
	// TODO : Before updating legislators, that workflow needs to be rethought, because the ddb sync now happens at the end, instead of the beginning
	public static boolean REINTERPRET_LEGISLATORS = false;
	
	public static boolean REINTERPRET_PARTIES = false;
	
	// Enables the agent to use web searches, but disables batch processing (which doesn't currently support web searches)
	public static boolean AGENTIC_WEB_SEARCH = true;
	
	@Inject
	private BatchBillRequestGenerator billRequestGenerator;
	
	@Inject
	private BatchLegislatorRequestGenerator legislatorRequestGenerator;
	
	@Inject
	private PartyInterpretationService partyInterpreter;
	
	@Inject
	private BatchOpenAIResponseImporter responseImporter;
	
	@Inject
	private LocalCachedS3Service s3;
	
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
	
	protected BuildReport report = new BuildReport();
	
	public static List<String> PROCESS_BILL_TYPE = Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> !CongressionalBillType.getIgnoredBillTypes().contains(bt)).map(bt -> bt.getName().toLowerCase()).collect(Collectors.toList());
	
	public BuildReport process() throws IOException
	{
		data.importAllDatasets();
		
		val buildDatasets = data.getBuildDatasets();
		
		initialDataSetup(buildDatasets);
		
		if (!AGENTIC_WEB_SEARCH)
			interpretBillPressArticles(buildDatasets);
		
		interpretBills(buildDatasets);
		pressBillInterpGenerator.recordLastPressQueries(); // We want to record that our press query is complete, but only after the bill has been updated and re-interpreted (otherwise we would need to query again if it fails halfway through)
		
		interpretLegislators(buildDatasets);
		interpretPartyStats(buildDatasets);
		
		System.out.println(report.toString());
		
		return report;
	}
	
	protected void initialDataSetup(List<PoliscoreDatasetIF> buildDatasets) {
		for (val dataset : buildDatasets) {
//			data.syncS3LegislatorImages(dataset);
			data.syncS3BillText(dataset);
			
			dataset.optimizeExists(s3, BillInterpretation.class);
			dataset.optimizeExists(s3, LegislatorInterpretation.class);
		}
	}
	
	@SneakyThrows
	private void interpretBillPressArticles(List<PoliscoreDatasetIF> buildDatasets) {
		if (INTERPRET_PRESS_BILLS) {
			List<InterpretationRequest> requests = pressBillInterpGenerator.process(buildDatasets);
			
			if (requests.size() > 0) {
				List<File> responses = openAi.processBatch(requests);
				
				for (File f : responses) {
					responseImporter.process(f);
				}
			}
		}
	}
	
	private void interpretBills(List<PoliscoreDatasetIF> buildDatasets) { interpretBills(buildDatasets, false); }
	@SneakyThrows private void interpretBills(List<PoliscoreDatasetIF> buildDatasets, boolean isRecursive) {
		if (INTERPRET_NEW_BILLS) {
			List<InterpretationRequest> requests = billRequestGenerator.process(buildDatasets, report, AGENTIC_WEB_SEARCH, isRecursive);
			
			if (requests.size() > 0) {
				List<File> responses;
				
				if (AGENTIC_WEB_SEARCH)
					responses = openAi.processBatchImmediately(requests);
				else
					responses = openAi.processBatch(requests);
				
				for (File f : responses) {
					responseImporter.process(f);
				}
				
				if (!isRecursive)
					interpretBills(buildDatasets, true);
			}
		}
	}
	
	@SneakyThrows
	private void interpretLegislators(List<PoliscoreDatasetIF> buildDatasets) {
		if (REINTERPRET_LEGISLATORS) {
			List<InterpretationRequest> requests = legislatorRequestGenerator.process(buildDatasets, report);
		
			if (requests.size() > 0) {
				List<File> responses;
				
				if (AGENTIC_WEB_SEARCH)
					responses = openAi.processBatchImmediately(requests);
				else
					responses = openAi.processBatch(requests);
				
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
	private void recalculateLegislators(PoliscoreDatasetIF dataset) {
		Log.info("Recalculating legislators for " + dataset.getDescription());
		
		List<String> legsWithoutInterp = new ArrayList<String>();
		List<String> legsWithoutSufficientInteractions = new ArrayList<String>();
		
		for (var leg : dataset.query(Legislator.class).stream()
//				.filter(l -> l.isMemberOfSession(data.getSession())) //  && s3.exists(LegislatorInterpretation.generateId(l.getId(), PoliscoreUtil.CURRENT_SESSION.getNumber()), LegislatorInterpretation.class)
				.collect(Collectors.toList()))
		{
			legInterp.updateInteractionsInterp(leg);
			
//			if (leg.getInteractions().size() < 100) {
//				legsWithoutSufficientInteractions.add(leg.getBioguideId());
//				continue;
//			}
			
			LegislatorInterpretation interp = new LegislatorInterpretation(dataset.getNamespace(), dataset.getCode(), leg.getCode(), OpenAIService.metadata(), null);
			val interpOp = s3.get(LegislatorInterpretation.generateId(dataset.getNamespace(), dataset.getCode(), leg.getCode()), LegislatorInterpretation.class);
			
			if (interpOp.isPresent()) { interp = interpOp.get(); }
			
			// If there exists an interp from a previous session, backfill the interactions until we get to 1000
			// We're skipping data from the 118th congress because the data in the 118th congress used a very primitive prompt
			if (!dataset.getCode().equals("119") && legInterp.getInteractionsForInterpretation(leg).size() < 1000) {
				// If an interpretation from this session doesn't exist, grab one from the previous session.
				var previousSession = data.getPreviousRegularSession(dataset.getRegularSession());
				
				if (previousSession != null && data.getDataset(previousSession) != null) {
					var previousDataset = data.getDataset(previousSession);
					val prevInterpOp = s3.get(LegislatorInterpretation.generateId(previousSession.getNamespace(), previousSession.getCode(), leg.getCode()), LegislatorInterpretation.class);
					
					if (prevInterpOp.isPresent()) {
						if (StringUtils.isBlank(interp.getShortExplain()))
							interp.setShortExplain(prevInterpOp.get().getShortExplain());
						if (StringUtils.isBlank(interp.getLongExplain()))
							interp.setLongExplain(prevInterpOp.get().getLongExplain());
						
						String prevLegId = Legislator.generateId(previousSession.getNamespace(), previousSession.getCode(), leg.getCode());
						
						val prevLeg = previousDataset.get(prevLegId, Legislator.class).orElseThrow();
						
						val prevInteracts = legInterp.getInteractionsForInterpretation(prevLeg).iterator();
						while (leg.getInteractionsPrivate1().size() < 1000 && prevInteracts.hasNext()) {
							val interact = prevInteracts.next();
							val interactInterpOp = s3.get(BillInterpretation.generateId(interact.getBillId(), null), BillInterpretation.class);
							
							if (interactInterpOp.isPresent() && interactInterpOp.get().getIssueStats() != null) {
								var issueStats = interactInterpOp.get().getIssueStats();
								interact.setRating(Math.round(issueStats.getRating() * interact.getJudgementWeight() * 0.9f));
								leg.getInteractionsPrivate1().add(interact);
							}
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
					.filter(i -> i.getIssueStats() != null && i.getRating() != null)
					.sorted((a,b) -> a.getDate().compareTo(b.getDate())).collect(Collectors.toCollection(LegislatorBillInteractionList::new)));
			
			leg.setInterpretation(interp);
			
			legInterp.calculateImpactAndRating(leg, interp);
			
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
	private void interpretPartyStats(List<PoliscoreDatasetIF> buildDatasets) {
		if (REINTERPRET_PARTIES) {
			List<InterpretationRequest> requests = partyInterpreter.process(buildDatasets);
			
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
