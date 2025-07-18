package us.poliscore.dataset;

import java.time.LocalDate;
import java.util.Base64;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import io.quarkus.arc.DefaultBean;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.SneakyThrows;
import lombok.val;
import software.amazon.awssdk.utils.StringUtils;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.images.StateLegislatorImageFetcher;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.legiscan.view.LegiscanBillView;
import us.poliscore.legiscan.view.LegiscanChamber;
import us.poliscore.legiscan.view.LegiscanMimeType;
import us.poliscore.legiscan.view.LegiscanPeopleView;
import us.poliscore.legiscan.view.LegiscanRollCallView;
import us.poliscore.legiscan.view.LegiscanSessionView;
import us.poliscore.legiscan.view.LegiscanSponsorView;
import us.poliscore.legiscan.view.LegiscanState;
import us.poliscore.legiscan.view.LegiscanStatus;
import us.poliscore.legiscan.view.LegiscanTextMetadataView;
import us.poliscore.legiscan.view.LegiscanVoteDetailView;
import us.poliscore.legiscan.view.LegiscanVoteStatus;
import us.poliscore.model.CongressionalSession;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.VoteStatus;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.Bill.BillSponsor;
import us.poliscore.model.bill.BillStatus;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislativeTerm;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillCosponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillVote;
import us.poliscore.openstates.OpenStatesDatasetAugmentor;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.storage.S3PersistenceService;

@ApplicationScoped
@Named("legiscan")
@DefaultBean
public class LegiscanDatasetProvider implements DatasetProvider {
	
//	@Inject
//	private SecretService secret;
	
	@Inject
	protected LegislatorService lService;
	
	@Inject protected OpenStatesDatasetAugmentor openstates;
	@Inject protected StateLegislatorImageFetcher stateImageFetcher;
	
	@Inject private S3PersistenceService s3;
	
	@Inject
	protected CachedLegiscanService legiscan;
	
	@Override
	public PoliscoreDataset importDataset(DeploymentConfig ref) {
		var state = namespaceToState(ref.getNamespace());
		var cached = legiscan.cacheDataset(state, ref.getYear());
		var session = buildSession(cached.getDataset().getSessionId(), cached.getDataset().getState(), cached.getDataset().getYearStart(), cached.getDataset().getYearEnd());
		
		PoliscoreDataset dataset = new PoliscoreDataset(session);
		
		for (var person : cached.getPeople().values()) {
			importLegislator(person, dataset);
		}
		openstates.augmentLegislators(dataset);
		
		for (var bill : cached.getBills().values()) {
			importBill(bill, dataset);
		}
		
		for (var vote : cached.getVotes().values()) {
			importRollCall(vote, dataset);
		}
		
		return dataset;
	}
	
	public static LegiscanState namespaceToState(LegislativeNamespace namespace) {
		return LegiscanState.fromAbbreviation(namespace.getNamespace().replace("us/", ""));
	}
	
	public static LegislativeSession buildSession(int sessionId, LegiscanState state, int yearStart, int yearEnd) {
		String key;
		LegislativeNamespace namespace;
		if (state.equals(LegiscanState.CONGRESS)) {
			key = String.valueOf(CongressionalSession.fromYear(yearEnd).getNumber());
			namespace = LegislativeNamespace.US_CONGRESS;
		} else {
			key = String.valueOf(sessionId);
			namespace = LegislativeNamespace.fromAbbreviation(state.getAbbreviation());
		}
		
		var start = LocalDate.of(yearStart, 1, 1);
		var end = LocalDate.of(yearEnd, 12, 31);
		
		return new LegislativeSession(start, end, key, namespace);
	}
	
	@Override
	public LegislativeSession getPreviousSession(LegislativeSession current) {
		LegiscanState state = LegiscanState.fromAbbreviation(current.getNamespace().toAbbreviation());
		
		LegiscanSessionView previous = null;
		for (var view : legiscan.getSessionList(state)) {
			if (view.getYearStart() == current.getStartDate().getYear() && view.getYearEnd() == current.getEndDate().getYear() && !view.isSpecial())
				return previous == null ? null : buildSession(previous.getSessionId(), previous.getState(), previous.getYearStart(), previous.getYearEnd());
			
			previous = view;
		}
		
		return null;
	}
	
	protected void importBill(LegiscanBillView view, PoliscoreDataset dataset) {
		val bill = new Bill();
		
		bill.setNumber(Integer.parseInt(view.getBillNumber().replaceAll("[^\\d]", "")));
		if (dataset.getSession().getNamespace().equals(LegislativeNamespace.US_CONGRESS))
    		bill.setType(toCongressionalBillType(view).name());
    	else
    		bill.setType(view.getBillType().getCode());
		bill.setId(Bill.generateId(dataset.getSession().getNamespace(), dataset.getSession().getCode(), bill.getType(), bill.getNumber()));
		
		bill.setName(view.getTitle());
    	bill.setOriginatingChamber(LegislativeChamber.fromLegiscanChamber(view.getHistory().get(0).getChamber()));
    	bill.setStatus(buildStatus(view, dataset.getSession()));
    	bill.setIntroducedDate(view.getHistory().getFirst().getDate());
    	bill.setSponsor(convertSponsor(view.getSponsors().getFirst(), dataset));
    	if (view.getSponsors().size() > 1)
    		bill.setCosponsors(view.getSponsors().subList(1, view.getSponsors().size()-1).stream().map(s -> convertSponsor(s, dataset)).collect(Collectors.toList()));
    	bill.setLastActionDate(view.getHistory().getLast().getDate());
    	bill.setLegiscanId(view.getBillId());
    	
    	
    	
    	if (bill.getSponsor() != null)
    	{
			val leg = dataset.get(bill.getSponsor().getLegislatorId(), Legislator.class);
			
			if (leg.isPresent()) {
				LegislatorBillSponsor interaction = new LegislatorBillSponsor();
				interaction.setLegId(leg.get().getId());
				interaction.setBillId(bill.getId());
				interaction.setDate(bill.getIntroducedDate());
				interaction.setBillName(bill.getName());
				interaction.setId(LegislatorBillSponsor.generateId(interaction.getLegId(), interaction.getDate(), interaction.getBillId()));
				leg.get().addBillInteraction(interaction);
				
				dataset.put(leg.get());
			}
    	}
    	
    	bill.getCosponsors().stream().filter(cs -> bill.getSponsor() == null || !bill.getSponsor().getLegislatorId().equals(cs.getLegislatorId())).forEach(cs -> {
    		if (!StringUtils.isBlank(cs.getLegislatorId())) {
	    		val leg = dataset.get(cs.getLegislatorId(), Legislator.class);
				
	    		if (leg.isPresent()) {
					LegislatorBillCosponsor interaction = new LegislatorBillCosponsor();
					interaction.setLegId(leg.get().getId());
					interaction.setBillId(bill.getId());
					interaction.setDate(bill.getIntroducedDate());
					interaction.setBillName(bill.getName());
					interaction.setId(LegislatorBillCosponsor.generateId(interaction.getLegId(), interaction.getDate(), interaction.getBillId()));
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
			legId = Legislator.generateId(dataset.getSession().getNamespace(), dataset.getSession().getCode(), view.getBioguideId());
		else
			legId = Legislator.generateId(dataset.getSession().getNamespace(), dataset.getSession().getCode(), String.valueOf(view.getPeopleId()));
		
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
	    final LegislativeNamespace ns = session.getNamespace();
	    
	    if (stat.equals(LegiscanStatus.INTRODUCED)) {
	        status.setDescription("Introduced in the " + chamber.getName(ns));
	        status.setProgress(0.0f);
	    } else if (stat.equals(LegiscanStatus.REFER)) {
	        status.setDescription((sessionOver ? "Died in " : "Referred to ") + " Committee");
	        status.setProgress(0.1f);
	    } else if (stat.equals(LegiscanStatus.REPORT_PASS)) {
	        status.setDescription("Committee Report: Pass Recommendation");
	        status.setProgress(0.2f);
	    } else if (stat.equals(LegiscanStatus.REPORT_DNP)) {
	        status.setDescription("Committee Report: Do Not Pass Recommendation");
	        status.setProgress(0.2f);
	    } else if (stat.equals(LegiscanStatus.ENGROSSED)) {
	        status.setDescription("Passed in " + chamber.getName(ns) + ", Sent to Second Chamber");
	        status.setProgress(0.4f);
	    } else if (stat.equals(LegiscanStatus.ENROLLED)) {
	        status.setDescription("Passed Both Chambers, Sent to " + executor);
	        status.setProgress(0.7f);
	    } else if (stat.equals(LegiscanStatus.PASSED)) {
	        status.setDescription("Bill Passed Both Chambers, " + (sessionOver ? "Killed by " : "Sent to ") + executor);
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

	protected void importRollCall(LegiscanRollCallView view, PoliscoreDataset dataset) {
		for (var vote : view.getVotes()) {
			importVote(view, vote, dataset);
		}
	}
	
	protected void importVote(LegiscanRollCallView rollCall, LegiscanVoteDetailView vote, PoliscoreDataset dataset) {
		Legislator leg;
		try
		{
			// TODO : I don't think this will work for congress (since the congress legislator code is bioguide id not people id) but we don't use legiscan for congress anyway
			leg = dataset.get(Legislator.generateId(dataset.getSession().getNamespace(), dataset.getSession().getCode(), String.valueOf(vote.getPeopleId())), Legislator.class).orElseThrow();
		}
		catch (NoSuchElementException ex)
		{
			Log.warn("Could not find legislator with people id " + vote.getPeopleId());
			return;
		}
		
		Bill bill;
		try
		{
			bill = dataset.query(Bill.class).stream().filter(b -> b.getLegiscanId() == rollCall.getBillId()).findFirst().get();
		}
		catch (NoSuchElementException ex)
		{
			Log.warn("Could not find bill with id " + rollCall.getBillId());
			return;
		}
		
		LegislatorBillVote interaction = new LegislatorBillVote(toVoteStatus(vote.getVote()));
		interaction.setLegId(leg.getId());
		interaction.setBillId(bill.getId());
		interaction.setDate(rollCall.getDate());
		interaction.setBillName(bill.getName());
		interaction.setId(LegislatorBillVote.generateId(interaction.getLegId(), interaction.getDate(), interaction.getBillId()));
		
		leg.addBillInteraction(interaction);
		
		dataset.put(leg);
	}
	
	public static VoteStatus toVoteStatus(LegiscanVoteStatus legiscanVoteStatus) {
	    if (legiscanVoteStatus == null) {
	        throw new IllegalArgumentException("LegiscanVoteStatus cannot be null.");
	    }

	    switch (legiscanVoteStatus) {
	        case YEA:
	            return VoteStatus.AYE;
	        case NAY:
	            return VoteStatus.NAY;
	        case ABSTAIN:
	            return VoteStatus.PRESENT;
	        case ABSENT:
	            return VoteStatus.NOT_VOTING;
	        default:
	            throw new IllegalStateException("Unexpected value: " + legiscanVoteStatus);
	    }
	}

	
	protected void importLegislator(LegiscanPeopleView view, PoliscoreDataset dataset) {
	    if (view == null || StringUtils.isBlank(view.getName())) return;

	    val leg = new Legislator();
	    
	    String legId;
		if (dataset.getSession().getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			legId = Legislator.generateId(dataset.getSession().getNamespace(), dataset.getSession().getCode(), view.getBioguideId());
		else
			legId = Legislator.generateId(dataset.getSession().getNamespace(), dataset.getSession().getCode(), String.valueOf(view.getPeopleId()));
		leg.setId(legId);
		
	    // Build and set name
	    val name = new Legislator.LegislatorName();
	    name.setFirst(view.getFirstName());
	    name.setLast(view.getLastName());
	    name.setOfficial_full(view.getName());
	    leg.setName(name);

	    // Legiscan doesn't actually provide birthday so we're augmenting our dataset later with OpenStates data (which often has birthdays)...
	    
	    var term = new LegislativeTerm();
	    term.setStartDate(dataset.getSession().getStartDate());
	    term.setEndDate(dataset.getSession().getEndDate());
	    term.setParty(Party.from(view.getParty().name()));
	    term.setState(view.getState());
	    term.setDistrict(StringUtils.isBlank(view.getDistrict()) ? null : view.getDistrict());
	    term.setChamber(LegislativeChamber.fromLegiscanRole(view.getRole()));
	    leg.getTerms().add(term);

	    // If active in current session, add to that session
	    if (leg.isMemberOfSession(dataset.getSession())) {
	        dataset.put(leg);
	    }
	}

	@Override
	public void syncS3LegislatorImages(PoliscoreDataset dataset) {
		openstates.syncS3LegislatorImages(dataset);
		stateImageFetcher.syncS3LegislatorImages(dataset);
	}
	
	@Override
	@SneakyThrows
	public void syncS3BillText(PoliscoreDataset dataset) {
		s3.optimizeExists(BillText.class, dataset.getSession().getKey());
		
		int count = 0;
		
		for (val bill : dataset.query(Bill.class)) {
			val legiBill = legiscan.getBill(bill.getLegiscanId());
			if (legiBill.getTexts().size() == 0) continue;
			
			// TODO : This won't allow for text updates or amendments
			if (s3.exists(BillText.generateId(bill.getId()), BillText.class)) continue;
			
			val metadata = legiBill.getTexts().stream().max(Comparator.comparing(LegiscanTextMetadataView::getDate)).get();
			
			val doc = legiscan.getBillText(metadata.getDocId());
			
			if (!doc.getMime().equals(LegiscanMimeType.PDF)) throw new UnsupportedOperationException("Unsupported bill text MIME type [" + doc.getMime().name() + "]");
			
			byte[] pdfBytes = Base64.getDecoder().decode(doc.getDoc());
			
			try (PDDocument document = Loader.loadPDF(pdfBytes)) {
	            PDFTextStripper stripper = new PDFTextStripper();
	            String text = stripper.getText(document);

	            BillText bt = BillText.factoryFromText(bill.getId(), text, doc.getDate());
				s3.put(bt);
				
				count++;
	        }
		}
		
		// TODO : This might not be necessary but I can't really remember why its here anymore
		s3.clearExistsOptimize(BillText.class, dataset.getSession().getKey());
		
		Log.info("Uploaded " + count + " new bill texts to s3 from Legiscan provider.");
	}

}
