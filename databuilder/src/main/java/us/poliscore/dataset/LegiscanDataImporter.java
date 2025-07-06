package us.poliscore.dataset;

import java.time.LocalDate;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.val;
import software.amazon.awssdk.utils.StringUtils;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DatasetReference;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.legiscan.view.LegiscanBillView;
import us.poliscore.legiscan.view.LegiscanChamber;
import us.poliscore.legiscan.view.LegiscanDatasetView;
import us.poliscore.legiscan.view.LegiscanPeopleView;
import us.poliscore.legiscan.view.LegiscanRollCallView;
import us.poliscore.legiscan.view.LegiscanSponsorView;
import us.poliscore.legiscan.view.LegiscanState;
import us.poliscore.legiscan.view.LegiscanStatus;
import us.poliscore.model.CongressionalSession;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.Bill.BillSponsor;
import us.poliscore.model.bill.BillStatus;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislativeTerm;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillCosponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.SecretService;

@ApplicationScoped
@Priority(1)
public class LegiscanDataImporter implements DatasetImporter {
	
	@Inject
	private SecretService secret;
	
	@Inject
	protected LegislatorService lService;
	
	@Override
	public PoliscoreDataset importDataset(DatasetReference ref) {
		CachedLegiscanService legiscan = CachedLegiscanService.builder(secret.getLegiscanSecret()).build();
		
		var state = namespaceToState(ref.getNamespace());
		var cached = legiscan.cacheDataset(state, ref.getYear());
		var session = sessionForDataset(cached.getDataset());
		
		PoliscoreDataset dataset = new PoliscoreDataset(session);
		
		for (var person : cached.getPeople().values()) {
			importLegislator(person, dataset);
		}
		
		for (var bill : cached.getBills().values()) {
			importBill(bill, dataset);
		}
		
		for (var vote : cached.getVotes().values()) {
			importVote(vote, dataset);
		}
		
		return dataset;
	}
	
	public static LegiscanState namespaceToState(LegislativeNamespace namespace) {
		return LegiscanState.fromAbbreviation(namespace.toString().replace("us/", ""));
	}
	
	public static LegislativeSession sessionForDataset(LegiscanDatasetView dataset) {
		String key;
		LegislativeNamespace namespace;
		if (dataset.getState().equals(LegiscanState.CONGRESS)) {
			key = String.valueOf(CongressionalSession.fromYear(dataset.getYearEnd()).getNumber());
			namespace = LegislativeNamespace.US_CONGRESS;
		} else {
			key = String.valueOf(dataset.getSessionId());
			namespace = LegislativeNamespace.fromAbbreviation(dataset.getState().getAbbreviation());
		}
		
		var start = LocalDate.of(dataset.getYearStart(), 1, 1);
		var end = LocalDate.of(dataset.getYearEnd(), 12, 31);
		
		return new LegislativeSession(start, end, key, namespace);
	}
	
	protected void importBill(LegiscanBillView view, PoliscoreDataset dataset) {
		val bill = new Bill();
    	bill.setName(view.getTitle());
    	bill.setSession(dataset.getSession());
    	bill.setNumber(Integer.parseInt(view.getBillNumber()));
    	bill.setOriginatingChamber(LegislativeChamber.fromLegiscanChamber(view.getHistory().get(0).getChamber()));
    	bill.setStatus(buildStatus(view, dataset.getSession()));
    	bill.setIntroducedDate(view.getHistory().getFirst().getDate());
    	bill.setSponsor(convertSponsor(view.getSponsors().getFirst(), dataset));
    	bill.setCosponsors(view.getSponsors().subList(1, view.getSponsors().size()-1).stream().map(s -> convertSponsor(s, dataset)).collect(Collectors.toList()));
    	bill.setLastActionDate(view.getHistory().getLast().getDate());
    	
    	if (dataset.getSession().getNamespace().equals(LegislativeNamespace.US_CONGRESS))
    		bill.setType(toCongressionalBillType(view).name());
    	else
    		bill.setType(view.getBillType().getCode());
    	
    	if (bill.getSponsor() != null)
    	{
			val leg = lService.getById(bill.getSponsor().getLegislatorId());
			
			if (leg.isPresent()) {
				LegislatorBillSponsor interaction = new LegislatorBillSponsor();
				interaction.setLegId(leg.get().getId());
				interaction.setBillId(bill.getId());
				interaction.setDate(bill.getIntroducedDate());
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
					interaction.setDate(bill.getIntroducedDate());
					interaction.setBillName(bill.getName());
					leg.get().addBillInteraction(interaction);
					
					dataset.put(leg.get());
	    		}
    		}
    	});
    	
    	dataset.put(bill);
	}
	
	public static CongressionalBillType toCongressionalBillType(LegiscanBillView bill) {
	    if (bill == null || bill.getBillTypeCode() == null || bill.getHistory() == null || bill.getHistory().isEmpty()) {
	        throw new IllegalArgumentException("Bill, billTypeCode, or history is missing");
	    }

	    String code = bill.getBillTypeCode();
	    LegiscanChamber chamber = bill.getHistory().get(0).getChamber();

	    // Treat UNICAM as SENATE
	    boolean isHouse = chamber == LegiscanChamber.HOUSE;
	    boolean isSenate = chamber == LegiscanChamber.SENATE || chamber == LegiscanChamber.UNICAM;

	    for (CongressionalBillType type : CongressionalBillType.values()) {
	        String name = type.getName();

	        if (isHouse) {
	            if (code.equals("B") && name.equals("hr")) return type;
	            if (code.equals("R") && name.equals("hres")) return type;
	            if (code.equals("CR") && name.equals("hconres")) return type;
	            if ((code.equals("JR") || code.equals("JRCA") || code.equals("CA")) && name.equals("hjres")) return type;
	        } else if (isSenate) {
	            if (code.equals("B") && name.equals("s")) return type;
	            if (code.equals("R") && name.equals("sres")) return type;
	            if (code.equals("CR") && name.equals("sconres")) return type;
	            if ((code.equals("JR") || code.equals("JRCA") || code.equals("CA")) && name.equals("sjres")) return type;
	        }
	    }

	    throw new IllegalArgumentException("No matching CongressionalBillType for code: " + code + " and chamber: " + chamber);
	}

	
	private BillSponsor convertSponsor(LegiscanSponsorView view, PoliscoreDataset dataset) {
		String legId;
		if (dataset.getSession().getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			legId = Legislator.generateId(dataset.getSession().getNamespace(), dataset.getSession(), view.getBioguideId());
		else
			legId = Legislator.generateId(dataset.getSession().getNamespace(), dataset.getSession(), view.getPeopleId());
		
		var leg = dataset.get(legId, Legislator.class).get();
		
		var sponsor = new BillSponsor(legId, leg.getName());
		sponsor.setParty(leg.getParty());
		return sponsor;
	}

	protected BillStatus buildStatus(LegiscanBillView view, LegislativeSession session) {
	    BillStatus status = new BillStatus();
	    status.setSourceStatus(view.getStatus().getCode());

	    final LegislativeChamber chamber = LegislativeChamber.fromLegiscanChamber(view.getHistory().get(0).getChamber());
	    final LegiscanStatus stat = view.getStatus();
	    final boolean sessionOver = session.isOver();
	    final String executor = session.getNamespace() == LegislativeNamespace.US_CONGRESS ? "President" : "Governor";

	    if (stat.equals(LegiscanStatus.INTRODUCED)) {
	        status.setDescription("Introduced in the " + chamber.getName());
	        status.setProgress(0.0f);
	    } else if (stat.equals(LegiscanStatus.REFER)) {
	        status.setDescription((sessionOver ? "Died in" : "Referred to") + " Committee");
	        status.setProgress(0.1f);
	    } else if (stat.equals(LegiscanStatus.REPORT_PASS)) {
	        status.setDescription("Committee Report: Pass Recommendation");
	        status.setProgress(0.2f);
	    } else if (stat.equals(LegiscanStatus.REPORT_DNP)) {
	        status.setDescription("Committee Report: Do Not Pass Recommendation");
	        status.setProgress(0.2f);
	    } else if (stat.equals(LegiscanStatus.ENGROSSED)) {
	        status.setDescription("Passed in " + chamber.getName() + ", Sent to Second Chamber");
	        status.setProgress(0.4f);
	    } else if (stat.equals(LegiscanStatus.ENROLLED)) {
	        status.setDescription("Passed Both Chambers, Sent to " + executor);
	        status.setProgress(0.7f);
	    } else if (stat.equals(LegiscanStatus.PASSED)) {
	        status.setDescription("Bill Passed Both Chambers, " + (sessionOver ? "Killed by " : "Sent to") + executor);
	        status.setProgress(0.8f);
	    } else if (stat.equals(LegiscanStatus.VETOED)) {
	        status.setDescription("Vetoed by " + executor);
	        status.setProgress(0.9f);
	    } else if (stat.equals(LegiscanStatus.OVERRIDE)) {
	        status.setDescription("Veto Overridden");
	        status.setProgress(1.0f);
	    } else if (stat.equals(LegiscanStatus.CHAPTERED)) {
	        status.setDescription("Law");
	        status.setProgress(1.0f);
	    } else if (stat.equals(LegiscanStatus.FAILED)) {
	        status.setDescription("Bill Failed");
	        status.setProgress(0.3f);
	    } else if (stat.equals(LegiscanStatus.DRAFT)) {
	        status.setDescription("Draft Bill (Not Yet Introduced)");
	        status.setProgress(0.0f);
	    } else if (stat.equals(LegiscanStatus.NA)) {
	        status.setDescription("Status Unknown");
	        status.setProgress(0.0f);
	    } else {
	        throw new UnsupportedOperationException("Unsupported status: " + stat);
	    }

	    return status;
	}

	protected void importVote(LegiscanRollCallView view, PoliscoreDataset dataset) {
		TODO();
	}
	
	protected void importLegislator(LegiscanPeopleView view, PoliscoreDataset dataset) {
	    if (view == null || StringUtils.isBlank(view.getName())) return;

	    val leg = new Legislator();

	    // Build and set name
	    val name = new Legislator.LegislatorName();
	    name.setFirst(view.getFirstName());
	    name.setLast(view.getLastName());
	    name.setOfficial_full(view.getName());
	    leg.setName(name);

	    // Set various IDs
	    leg.setBioguideId(view.getBioguideId());
	    leg.setLegiscanId(view.getPeopleId());

	    // Set birthday with fallback
	    // TODO : Legiscan doesn't provide birthday...
//	    LocalDate birthday = PoliscoreUtil.parseDate(view.getBio() != null ? view.getBio().getBirthday() : null);
//	    leg.setBirthday(birthday);
	    
	    var term = new LegislativeTerm();
	    term.setSession(dataset.getSession());
	    term.setParty(Party.from(view.getPartyCode()));
	    term.setState(view.getState());
	    term.setDistrict(view.getDistrict());
	    term.setChamber(LegislativeChamber.fromLegiscanRole(view.getRole()));
	    leg.getTerms().add(term);

	    // If active in current session, add to that session
	    if (leg.isMemberOfSession(dataset.getSession())) {
	        leg.setSession(dataset.getSession());
	        dataset.put(leg);
	    }
	}
}
