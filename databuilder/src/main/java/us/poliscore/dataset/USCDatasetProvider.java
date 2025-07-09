package us.poliscore.dataset;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import software.amazon.awssdk.utils.StringUtils;
import us.poliscore.Environment;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DatasetReference;
import us.poliscore.PoliscoreUtil;
import us.poliscore.entrypoint.GPOBulkBillTextFetcher;
import us.poliscore.images.CongressionalLegislatorImageFetcher;
import us.poliscore.model.CongressionalSession;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.VoteStatus;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillStatus;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislatorLegislativeTermSortedSet;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillCosponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillVote;
import us.poliscore.service.BillService;
import us.poliscore.service.LegislatorService;
import us.poliscore.view.USCBillView;
import us.poliscore.view.USCLegislatorView;
import us.poliscore.view.USCLegislatorView.USCLegislativeTerm;
import us.poliscore.view.USCRollCallData;
import us.poliscore.view.USCRollCallData.USCRollCallVote;

@ApplicationScoped
public class USCDatasetProvider implements DatasetProvider {
	
	public static boolean memorizedRollCall = false;
	
	public static boolean updatedLegislatorFiles = false;
	
	@Inject
	protected LegislatorService lService;
	
	@Inject
	private CongressionalLegislatorImageFetcher congressionalImageFetcher;
	
	@Inject
	private GPOBulkBillTextFetcher billTextFetcher;
	
	@Override
	public PoliscoreDataset importDataset(DatasetReference ref) {
		LegislativeSession session = new LegislativeSession(LocalDate.of(ref.getYear() - 1, 1, 1), LocalDate.of(ref.getYear(), 12, 31), String.valueOf(CongressionalSession.fromYear(ref.getYear()).getNumber()), LegislativeNamespace.US_CONGRESS);
		PoliscoreDataset dataset = new PoliscoreDataset(session);
		
		updateUscLegislators();
		importUSCLegislators(dataset);
		importUscBills(dataset);
		importUscVotes(dataset);
		
		return dataset;
	}
	
	@SneakyThrows
	public void syncS3LegislatorImages(PoliscoreDataset dataset) {
		congressionalImageFetcher.syncS3LegislatorImages(dataset);
	}
	
	@SneakyThrows
	private void updateUscLegislators()
	{
		if (updatedLegislatorFiles) return;
		
		Log.info("Updating USC legislators resource files");
		
		File dbRes = new File(Environment.getDeployedPath(), "../../databuilder/src/main/resources");
		
		FileUtils.copyURLToFile(URI.create("https://unitedstates.github.io/congress-legislators/legislators-current.json").toURL(), new File(dbRes, "legislators-current.json"));
		FileUtils.copyURLToFile(URI.create("https://unitedstates.github.io/congress-legislators/legislators-historical.json").toURL(), new File(dbRes, "legislators-historical.json"));
		
		updatedLegislatorFiles = true;
	}
	
	@Override
	public LegislativeSession getPreviousSession(LegislativeSession current) {
		int endYear = current.getEndDate().getYear() - 2;
		return new LegislativeSession(LocalDate.of(endYear - 1, 1, 1), LocalDate.of(endYear, 12, 31), String.valueOf(CongressionalSession.fromYear(endYear).getNumber()), LegislativeNamespace.US_CONGRESS);
	}
	
	@SneakyThrows
	public void importUSCLegislators(PoliscoreDataset dataset)
	{
		if (dataset.count(Legislator.class) > 0) return;
		
		importUSCLegislator(dataset, "/legislators-current.json");
		importUSCLegislator(dataset, "/legislators-historical.json");
		
		Log.info("Imported " + dataset.count(Legislator.class) + " politicians");
	}

	private void importUSCLegislator(PoliscoreDataset dataset, String file) throws IOException, JsonProcessingException {
		ObjectMapper mapper = PoliscoreUtil.getObjectMapper();
		JsonNode jn = mapper.readTree(LegislatorService.class.getResourceAsStream(file));
		Iterator<JsonNode> it = jn.elements();
		while (it.hasNext())
		{
			USCLegislatorView view = mapper.treeToValue(it.next(), USCLegislatorView.class);
			
			// Skip legislators with terms in historical territories
	        if (view.getTerms().stream().anyMatch(term -> Set.of("DK", "OL", "PI").contains(term.getState().toUpperCase()))) {
	            continue;
	        }
			
			Legislator leg = new Legislator();
			leg.setName(view.getName().convert());
			leg.setBioguideId(view.getId().getBioguide());
			leg.setLisId(view.getId().getLis());
			leg.setBirthday(view.getBio().getBirthday());
			leg.setTerms(view.getTerms().stream()
					.map(t -> t.convert())
					.collect(Collectors.toCollection(LegislatorLegislativeTermSortedSet::new)));
			
			if (leg.isMemberOfSession(dataset.getSession()))
			{
				leg.setSession(dataset.getSession());
				
				dataset.put(leg);
			}
		}
	}
	
	@SneakyThrows
	public void importUscVotes(PoliscoreDataset dataset) {
		if (memorizedRollCall) return;
		
		long totalVotes = 0;
		long skipped = 0;
		
		for (File fCongress : Arrays.asList(PoliscoreUtil.USC_DATA.listFiles()).stream()
				.filter(f -> f.getName().matches("\\d+") && f.isDirectory())
				.sorted((a,b) -> a.getName().compareTo(b.getName()))
				.collect(Collectors.toList()))
		{
			Integer congressNum = Integer.valueOf(dataset.getSession().getKey());
			if (!congressNum.equals(Integer.valueOf(fCongress.getName()))) continue;
			
			Log.info("Processing " + fCongress.getName() + " congress");
			
			for (File data : PoliscoreUtil.allFilesWhere(new File(fCongress, "votes"), f -> f.getName().equals("data.json")))
			{
				try (var fos = new FileInputStream(data))
				{
					if (importRollCall(dataset, fos))
						totalVotes++;
					else
						skipped++;
				}
				catch (Exception e)
				{
					Log.error("Exception encountered while processing roll call file [" + data.getAbsolutePath() + "]", e);
				}
			}
		}
		
		memorizedRollCall = true;
		
		Log.info("Imported " + totalVotes + " votes. Skipped " + skipped);
	}
	
	@SneakyThrows
	protected boolean importRollCall(PoliscoreDataset dataset, InputStream is)
	{
		USCRollCallData rollCall = PoliscoreUtil.getObjectMapper().readValue(is, USCRollCallData.class);
		
		// There are a lot of roll call categories that we don't care about. Quorum is one of them.
		if (!rollCall.getCategory().contains("passage")) return false;
//		if (rollCall.getBill() == null) return false;
		
		// There are some bill types we don't care about. Don't bother printing noisy warnings or anything
		if (CongressionalBillType.getIgnoredBillTypes().contains(CongressionalBillType.valueOf(rollCall.getBill().getType().toUpperCase()))) return false;
		
		rollCall.getVotes().getAffirmative().forEach(v -> importRollCallHelper(dataset, rollCall, v, VoteStatus.AYE));
		rollCall.getVotes().getNegative().forEach(v -> importRollCallHelper(dataset, rollCall, v, VoteStatus.NAY));
		
		// At the moment these are just pointless noise so we're ignoring them.
//		rollCall.getVotes().getNotVoting().forEach(v -> process(rollCall, v, VoteStatus.NOT_VOTING));
//		rollCall.getVotes().getPresent().forEach(v -> process(rollCall, v, VoteStatus.PRESENT));
		
		return true;
	}
	
	protected void importRollCallHelper(PoliscoreDataset dataset, USCRollCallData rollCall, USCRollCallVote vote, VoteStatus vs)
	{
		Legislator leg;
		try
		{
			if (vote.getId().length() == 4 && vote.getId().startsWith("S"))
				leg = dataset.query(Legislator.class).stream().filter(l -> vote.getId().equals(l.getLisId())).findFirst().orElseThrow();
			else
				leg = dataset.get(Legislator.generateId(LegislativeNamespace.US_CONGRESS, dataset.getSession(), vote.getId()), Legislator.class).orElseThrow();
		}
		catch (NoSuchElementException ex)
		{
			Log.warn("Could not find legislator with bioguide id " + vote.getId());
			return;
		}
		
		Bill bill;
		var billView = rollCall.getBill();
		var billId = Bill.generateId(dataset.getSession(), CongressionalBillType.valueOf(billView.getType().toUpperCase()).name(), billView.getNumber());
		try
		{
			bill = dataset.get(billId, Bill.class).orElseThrow();
		}
		catch (NoSuchElementException ex)
		{
			Log.warn("Could not find bill with id " + billId);
			return;
		}
		
		LegislatorBillVote interaction = new LegislatorBillVote(vs);
		interaction.setLegId(leg.getId());
		interaction.setBillId(bill.getId());
		interaction.setDate(rollCall.getDate().toLocalDate());
		interaction.setBillName(bill.getName());
		
		leg.addBillInteraction(interaction);
		
		dataset.put(leg);
	}
	
	@SneakyThrows
	public void importUscBills(PoliscoreDataset dataset) {
		if (dataset.query(Bill.class).size() > 0) return;
		
		long totalBills = 0;
		
		for (File fCongress : Arrays.asList(PoliscoreUtil.USC_DATA.listFiles()).stream()
				.filter(f -> f.getName().matches("\\d+") && f.isDirectory())
				.sorted((a,b) -> a.getName().compareTo(b.getName()))
				.collect(Collectors.toList()))
		{
			Integer congressNum = Integer.valueOf(dataset.getSession().getKey());
			if (!congressNum.equals(Integer.valueOf(fCongress.getName()))) continue;
			
			Log.info("Processing " + fCongress.getName() + " congress");
			
			for (val bt : BillService.PROCESS_BILL_TYPE)
			{
				Log.info("Processing bill types " + bt + " congress");
				
				for (File data : PoliscoreUtil.allFilesWhere(new File(fCongress, "bills/" + bt), f -> f.getName().equals("data.json")))
				{
					try (var fos = new FileInputStream(data))
					{
						importUscBill(dataset, fos);
						totalBills++;
					}
				}
			}
		}
		
		Log.info("Imported " + totalBills + " bills");
	}
	
	public BillStatus buildStatus(USCBillView view) {
	    BillStatus status = new BillStatus();
	    status.setSourceStatus(view.getStatus());
	    
	    final LegislativeChamber chamber = CongressionalBillType.getOriginatingChamber(CongressionalBillType.valueOf(view.getBill_type().toUpperCase()));
	    final String stat = view.getStatus().toUpperCase();
	    final boolean sessionOver = CongressionalSession.of(Integer.parseInt(view.getCongress())).isOver();

	    if (stat.equals("INTRODUCED")) {
	        status.setDescription("Introduced in the " + chamber.getName());
	        status.setProgress(0.0f);
	    }
	    else if (stat.equals("REFERRED")) {
	        status.setDescription((sessionOver ? "Died in" : "Referred to") + " Committee");
	        status.setProgress(0.1f);
	    }
	    else if (stat.equals("REPORTED")) {
	        status.setDescription(sessionOver ? ("Expired Awaiting " + chamber.getName() + " Debate") : ("Awaiting " + chamber.getName() + " Debate"));
	        status.setProgress(0.2f);
	    }
	    else if (stat.equals("PROV_KILL:SUSPENSIONFAILED")) {
	        status.setDescription("Provisionally Killed (suspension of rules)");
	        status.setProgress(0.3f);
	    }
	    else if (stat.equals("PROV_KILL:CLOTUREFAILED")) {
	        status.setDescription("Provisionally Killed (filibustered)");
	        status.setProgress(0.3f);
	    }
	    else if (stat.startsWith("FAIL:ORIGINATING")) {
	        // e.g., FAIL:ORIGINATING:HOUSE or FAIL:ORIGINATING:SENATE
	        status.setDescription("Failed " + chamber.getName() + " Vote");
	        status.setProgress(0.3f);
	    }
	    else if (stat.equals("PASSED:SIMPLERES")) {
	        status.setDescription("Simple Resolution Passed in the " + chamber.getName());
	        // For simple resolutions, passing is the end of the road
	        status.setProgress(1.0f);
	    }
	    else if (stat.equals("PASSED:CONSTAMEND")) {
	        status.setDescription("Constitutional Amendment Passed by Both Chambers");
	        // After passing both chambers, it goes to the states, so from Congress's perspective it’s “final”
	        status.setProgress(1.0f);
	    }
	    else if (stat.equals("PASS_OVER:HOUSE")) {
	        status.setDescription("Passed in House, " + (sessionOver ? "Died in " : "Sent to") + " Senate");
	        status.setProgress(0.4f);
	    }
	    else if (stat.equals("PASS_OVER:SENATE")) {
	        status.setDescription("Passed in Senate, " + (sessionOver ? "Died in " : "Sent to") + " House");
	        status.setProgress(0.4f);
	    }
	    else if (stat.equals("PASSED:CONCURRENTRES")) {
	        status.setDescription("Concurrent Resolution Passed by Both Chambers");
	        status.setProgress(1.0f);
	    }
	    else if (stat.equals("FAIL:SECOND:HOUSE")) {
	        status.setDescription("Failed in Second Chamber (House)");
	        status.setProgress(0.5f);
	    }
	    else if (stat.equals("FAIL:SECOND:SENATE")) {
	        status.setDescription("Failed in Second Chamber (Senate)");
	        status.setProgress(0.5f);
	    }
	    else if (stat.equals("PASS_BACK:HOUSE")) {
	        status.setDescription("Bill Passed with Changes, Returning to House");
	        status.setProgress(0.5f);
	    }
	    else if (stat.equals("PASS_BACK:SENATE")) {
	        status.setDescription("Bill Passed with Changes, Returning to Senate");
	        status.setProgress(0.5f);
	    }
	    else if (stat.equals("PROV_KILL:PINGPONGFAIL")) {
	        status.setDescription("Ping-Pong Negotiations Failed (Provisional Kill)");
	        status.setProgress(0.5f);
	    }
	    else if (stat.equals("PASSED:BILL")) {
	        status.setDescription("Bill Passed Both Chambers, " + (sessionOver ? "Killed by " : "Sent to") + " the President");
	        status.setProgress(0.8f);
	    }
	    else if (stat.equals("CONFERENCE:PASSED:HOUSE")) {
	        status.setDescription("Conference Report Passed in House, Awaiting Senate");
	        status.setProgress(0.6f);
	    }
	    else if (stat.equals("CONFERENCE:PASSED:SENATE")) {
	        status.setDescription("Conference Report Passed in Senate, Awaiting House");
	        status.setProgress(0.6f);
	    }
	    else if (stat.equals("ENACTED:SIGNED")) {
	        status.setDescription("Law");
	        status.setProgress(1.0f);
	    }
	    else if (stat.equals("PROV_KILL:VETO")) {
	        status.setDescription("Provisionally Killed by Veto (Override Possible)");
	        status.setProgress(0.7f);
	    }
	    else if (stat.equals("VETOED:POCKET")) {
	        status.setDescription("Pocket Veto - Bill is Dead");
	        status.setProgress(0.8f);
	    }
	    else if (stat.equals("VETOED:OVERRIDE_FAIL_ORIGINATING:HOUSE")) {
	        status.setDescription("Veto Override Failed in House (Originating Chamber)");
	        status.setProgress(0.0f);
	    }
	    else if (stat.equals("VETOED:OVERRIDE_FAIL_ORIGINATING:SENATE")) {
	        status.setDescription("Veto Override Failed in Senate (Originating Chamber)");
	        status.setProgress(0.0f);
	    }
	    else if (stat.equals("VETOED:OVERRIDE_PASS_OVER:HOUSE")) {
	        status.setDescription("Veto Override Passed in House, " + (sessionOver ? "Died in " : "Sent to") + " Senate");
	        status.setProgress(0.8f);
	    }
	    else if (stat.equals("VETOED:OVERRIDE_PASS_OVER:SENATE")) {
	        status.setDescription("Veto Override Passed in Senate, " + (sessionOver ? "Died in " : "Sent to") + " House");
	        status.setProgress(0.8f);
	    }
	    else if (stat.equals("VETOED:OVERRIDE_FAIL_SECOND:HOUSE")) {
	        status.setDescription("Veto Override Passed in Senate but Failed in House");
	        status.setProgress(0.0f);
	    }
	    else if (stat.equals("VETOED:OVERRIDE_FAIL_SECOND:SENATE")) {
	        status.setDescription("Veto Override Passed in House but Failed in Senate");
	        status.setProgress(0.0f);
	    }
	    else if (stat.equals("ENACTED:VETO_OVERRIDE")) {
	        status.setDescription("Law");
	        status.setProgress(1.0f);
	    }
	    else if (stat.equals("ENACTED:TENDAYRULE")) {
	        status.setDescription("Law");
	        status.setProgress(1.0f);
	    }
	    else {
	    	throw new UnsupportedOperationException("Unsupported status: " + stat);
	    }

	    return status;
	}
	
	@SneakyThrows
	protected void importUscBill(PoliscoreDataset dataset, FileInputStream fos) {
		val view = PoliscoreUtil.getObjectMapper().readValue(fos, USCBillView.class);
		
//		String text = fetchBillText(view.getUrl());
    	
    	val bill = new Bill();
//    	bill.setText(text);
    	bill.setName(view.getBillName());
    	bill.setSession(dataset.getSession());
    	bill.setType(CongressionalBillType.valueOf(view.getBill_type().toUpperCase()).name());
    	bill.setNumber(Integer.parseInt(view.getNumber()));
    	bill.setStatus(buildStatus(view));
//    	bill.setStatusUrl(view.getUrl());
    	bill.setIntroducedDate(view.getIntroduced_at());
    	bill.setSponsor(view.getSponsor() == null ? null : view.getSponsor().convert(dataset));
    	bill.setCosponsors(view.getCosponsors().stream().map(s -> s.convert(dataset)).collect(Collectors.toList()));
    	bill.setLastActionDate(view.getLastActionDate());
    	
    	if (view.getSponsor() != null && !StringUtils.isBlank(view.getSponsor().getBioguide_id()))
    	{
			val leg = lService.getById(bill.getSponsor().getLegislatorId());
			
			if (leg.isPresent()) {
				LegislatorBillSponsor interaction = new LegislatorBillSponsor();
				interaction.setLegId(leg.get().getId());
				interaction.setBillId(bill.getId());
				interaction.setDate(view.getIntroduced_at());
				interaction.setBillName(bill.getName());
				leg.get().addBillInteraction(interaction);
				
				dataset.put(leg.get());
			}
    	}
    	
    	bill.getCosponsors().stream().filter(cs -> bill.getSponsor() == null || !bill.getSponsor().getLegislatorId().equals(cs.getLegislatorId())).forEach(cs -> {
    		if (!StringUtils.isBlank(cs.getLegislatorId())) {
	    		val leg = lService.getById(cs.getLegislatorId());
				
	    		if (leg.isPresent()) {
					LegislatorBillCosponsor interaction = new LegislatorBillCosponsor();
					interaction.setLegId(leg.get().getId());
					interaction.setBillId(bill.getId());
					interaction.setDate(view.getIntroduced_at());
					interaction.setBillName(bill.getName());
					leg.get().addBillInteraction(interaction);
					
					dataset.put(leg.get());
	    		}
    		}
    	});
    	
    	dataset.put(bill);
	}

	@Override
	public void syncS3BillText(PoliscoreDataset dataset) {
		billTextFetcher.process();
	}
}
