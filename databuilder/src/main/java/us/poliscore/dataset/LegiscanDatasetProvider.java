package us.poliscore.dataset;

import java.time.LocalDate;
import java.util.Base64;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.DefaultBean;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.SneakyThrows;
import lombok.val;
import software.amazon.awssdk.utils.StringUtils;
import us.poliscore.PoliscoreCompositeDataset;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.dataset.augmentation.PoliscoreDatasetAugmentor;
import us.poliscore.images.StateLegislatorImageFetcher;
import us.poliscore.legiscan.cache.CachedLegiscanDatasetResult;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.legiscan.view.LegiscanBillType;
import us.poliscore.legiscan.view.LegiscanBillView;
import us.poliscore.legiscan.view.LegiscanChamber;
import us.poliscore.legiscan.view.LegiscanDatasetView;
import us.poliscore.legiscan.view.LegiscanMimeType;
import us.poliscore.legiscan.view.LegiscanPeopleView;
import us.poliscore.legiscan.view.LegiscanRollCallView;
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
import us.poliscore.service.LegislatorService;
import us.poliscore.service.storage.S3PersistenceService;

@ApplicationScoped
@Named("legiscan")
@DefaultBean
public class LegiscanDatasetProvider implements DatasetProvider {
	
	private static final Logger logger = LoggerFactory.getLogger(LegiscanDatasetProvider.class);
	
//	@Inject
//	private SecretService secret;
	
	@Inject
	protected LegislatorService lService;
	
	@Inject protected PoliscoreDatasetAugmentor psLegScraper;
	@Inject protected StateLegislatorImageFetcher stateImageFetcher;
	
	@Inject private S3PersistenceService s3;
	
	@Inject
	protected CachedLegiscanService legiscan;
	
	@Override
	public PoliscoreDatasetIF importDataset(DeploymentConfig ref) {
		var state = namespaceToState(ref.getNamespace());
		val views = legiscan.getDatasetList(state, ref.getYear());
		
		PoliscoreDatasetIF dataset;
		
		if (views.size() == 1) {
			dataset = importDataset(views.get(0), ref, null);
		} else {
			dataset = new PoliscoreCompositeDataset(ref);
			 
			val regularView = views.stream().filter(v -> !v.isSpecial()).findFirst().get();
			val regularDataset = importDataset(regularView, ref, null);
			((PoliscoreCompositeDataset)dataset).addDataset(regularDataset);
			
			for (val view : views) {
				if (view != regularView)
					((PoliscoreCompositeDataset)dataset).addDataset(importDataset(view, ref, regularDataset));
			}
		}
		
		return dataset;
	}
	
	protected PoliscoreDataset importDataset(LegiscanDatasetView view, DeploymentConfig ref, PoliscoreDataset regularDataset) {
		CachedLegiscanDatasetResult cached = legiscan.cacheDataset(view);
		
		var session = buildSession(!view.isSpecial(), cached.getDataset().getSessionId(), cached.getDataset().getState(), cached.getDataset().getYearStart(), cached.getDataset().getYearEnd());
		
		PoliscoreDataset dataset = new PoliscoreDataset(session, ref);
		if (regularDataset == null) {
			regularDataset = dataset;
			for (var person : cached.getPeople().values()) {
				importLegislator(person, regularDataset);
			}
			psLegScraper.augmentLegislators(dataset);
		}
		
		for (var bill : cached.getBills().values()) {
			importBill(bill, dataset, regularDataset);
		}
		
		for (var vote : cached.getVotes().values()) {
			importRollCall(vote, dataset, regularDataset);
		}
		
		return dataset;
	}
	
	public static LegiscanState namespaceToState(LegislativeNamespace namespace) {
		return LegiscanState.fromAbbreviation(namespace.getNamespace().replace("us/", ""));
	}
	
	public static LegislativeSession buildSession(boolean regular, int sessionId, LegiscanState state, int yearStart, int yearEnd) {
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
		
		return new LegislativeSession(regular, start, end, key, namespace);
	}
	
//	@Override
//	public LegislativeSession getPreviousRegularSession(LegislativeSession current) {
//		LegiscanState state = LegiscanState.fromAbbreviation(current.getNamespace().toAbbreviation());
//		
//		LegiscanSessionView previous = null;
//		for (var view : legiscan.getSessionList(state)) {
//			if (view.getYearStart() == current.getStartDate().getYear() && view.getYearEnd() == current.getEndDate().getYear() && !view.isSpecial())
//				return previous == null ? null : buildSession(true, previous.getSessionId(), previous.getState(), previous.getYearStart(), previous.getYearEnd());
//			
//			previous = view;
//		}
//		
//		return null;
//	}
	
	protected String getChamberCode(LegislativeChamber chamber) {
		if (chamber.equals(LegislativeChamber.UPPER)) {
			return "S";
		} else if (chamber.equals(LegislativeChamber.LOWER)) {
			return "H";
		} else if (chamber.equals(LegislativeChamber.JOINT)) {
			return "J";
		} else {
			throw new UnsupportedOperationException("Unsupported chamber: " + chamber.name());
		}
	}
	
	protected void importBill(LegiscanBillView view, PoliscoreDataset dataset, PoliscoreDataset regularDataset) {
		val bill = new Bill();
		
		bill.setNumber(Integer.parseInt(view.getBillNumber().replaceAll("[^\\d]", "")));
		
		if (view.getHistory().size() == 0) {
			logger.warn("Legiscan bill " + view.getBillId() + " did not have any history and thus cannot be imported.");
			return;
		}
			
		bill.setOriginatingChamber(LegislativeChamber.fromLegiscanChamber(view.getHistory().get(0).getChamber()));
		
		if (dataset.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
    		bill.setType(toCongressionalBillType(view).name());
    	else
    		bill.setType(getChamberCode(bill.getOriginatingChamber()) + view.getBillType().getCode());
		
		bill.setId(Bill.generateId(dataset.getNamespace(), dataset.getCode(), bill.getType(), bill.getNumber()));
		
		bill.setName(view.getTitle());
    	bill.setStatus(buildStatus(view, regularDataset.getSession()));
    	bill.setIntroducedDate(view.getHistory().getFirst().getDate());
    	bill.setSponsor(convertSponsor(view.getSponsors().getFirst(), regularDataset));
    	if (view.getSponsors().size() > 1)
    		bill.setCosponsors(view.getSponsors().subList(1, view.getSponsors().size()).stream().map(s -> convertSponsor(s, regularDataset)).collect(Collectors.toList()));
    	bill.setLastActionDate(view.getHistory().getLast().getDate());
    	bill.setLegiscanId(view.getBillId());
    	bill.setOfficialUrl(view.getStateLink());
    	
    	
    	
    	if (bill.getSponsor() != null)
    	{
			val leg = regularDataset.get(bill.getSponsor().getLegislatorId(), Legislator.class);
			
			if (leg.isPresent()) {
				LegislatorBillSponsor interaction = new LegislatorBillSponsor();
				interaction.setLegId(leg.get().getId());
				interaction.setBillId(bill.getId());
				interaction.setDate(bill.getIntroducedDate());
				interaction.setBillName(bill.getName());
				interaction.setId(LegislatorBillSponsor.generateId(interaction.getLegId(), interaction.getDate(), interaction.getBillId()));
				leg.get().addBillInteraction(interaction);
			}
    	}
    	
    	bill.getCosponsors().stream().filter(cs -> bill.getSponsor() == null || !bill.getSponsor().getLegislatorId().equals(cs.getLegislatorId())).forEach(cs -> {
    		if (!StringUtils.isBlank(cs.getLegislatorId())) {
	    		val leg = regularDataset.get(cs.getLegislatorId(), Legislator.class);
				
	    		if (leg.isPresent()) {
					LegislatorBillCosponsor interaction = new LegislatorBillCosponsor();
					interaction.setLegId(leg.get().getId());
					interaction.setBillId(bill.getId());
					interaction.setDate(bill.getIntroducedDate());
					interaction.setBillName(bill.getName());
					interaction.setId(LegislatorBillCosponsor.generateId(interaction.getLegId(), interaction.getDate(), interaction.getBillId()));
					leg.get().addBillInteraction(interaction);
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

	
	private BillSponsor convertSponsor(LegiscanSponsorView view, PoliscoreDataset regularDataset) {
		String legId;
		if (regularDataset.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			legId = Legislator.generateId(regularDataset.getNamespace(), regularDataset.getCode(), view.getBioguideId());
		else
			legId = Legislator.generateId(regularDataset.getNamespace(), regularDataset.getCode(), String.valueOf(view.getPeopleId()));
		
		var leg = regularDataset.get(legId, Legislator.class).get();
		
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
	        status.setDescription("Passed Both Chambers, " + (sessionOver ? "Killed by " : "Sent to ") + executor);
	        status.setProgress(0.7f);
	    } else if (stat.equals(LegiscanStatus.PASSED)) {
	        status.setDescription(view.getBillType().equals(LegiscanBillType.BILL) ? "Law" : "Passed");
	        status.setProgress(1.0f);
	    } else if (stat.equals(LegiscanStatus.VETOED)) {
	        status.setDescription("Vetoed by " + executor);
	        status.setProgress(0.9f);
	    } else if (stat.equals(LegiscanStatus.OVERRIDE)) {
	        status.setDescription("Veto Overridden");
	        status.setProgress(1.0f);
	    } else if (stat.equals(LegiscanStatus.CHAPTERED)) {
	        status.setDescription(view.getBillType().equals(LegiscanBillType.BILL) ? "Law" : "Chaptered");
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

	protected void importRollCall(LegiscanRollCallView view, PoliscoreDataset dataset, PoliscoreDataset regularDataset) {
		for (var vote : view.getVotes()) {
			importVote(view, vote, dataset, regularDataset);
		}
	}
	
	protected void importVote(LegiscanRollCallView rollCall, LegiscanVoteDetailView vote, PoliscoreDataset dataset, PoliscoreDataset regularDataset) {
		Legislator leg;
		try
		{
			// TODO : I don't think this will work for congress (since the congress legislator code is bioguide id not people id) but we don't use legiscan for congress anyway
			leg = regularDataset.get(Legislator.generateId(regularDataset.getNamespace(), regularDataset.getCode(), String.valueOf(vote.getPeopleId())), Legislator.class).orElseThrow();
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

	
	protected void importLegislator(LegiscanPeopleView view, PoliscoreDataset regularDataset) {
	    if (view == null || StringUtils.isBlank(view.getName())) return;

	    val leg = new Legislator();
	    leg.setLegiscanId(view.getPeopleId());
	    
	    String legId;
		if (regularDataset.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			legId = Legislator.generateId(regularDataset.getNamespace(), regularDataset.getCode(), view.getBioguideId());
		else
			legId = Legislator.generateId(regularDataset.getNamespace(), regularDataset.getCode(), String.valueOf(view.getPeopleId()));
		leg.setId(legId);
		
	    // Build and set name
	    val name = new Legislator.LegislatorName();
	    name.setFirst(view.getFirstName());
	    name.setLast(view.getLastName());
	    name.setOfficial_full(view.getName());
	    leg.setName(name);

	    // Legiscan doesn't actually provide birthday so we're augmenting our dataset later with OpenStates data (which often has birthdays)...
	    
	    var term = new LegislativeTerm();
	    term.setStartDate(regularDataset.getSession().getStartDate());
	    term.setEndDate(regularDataset.getSession().getEndDate());
	    term.setParty(Party.from(view.getParty().name()));
	    term.setState(view.getState());
	    term.setDistrict(StringUtils.isBlank(view.getDistrict()) ? null : view.getDistrict());
	    term.setChamber(LegislativeChamber.fromLegiscanRole(view.getRole()));
	    leg.getTerms().add(term);
	    
	    regularDataset.put(leg);
	}

	@Override
	public void syncS3LegislatorImages(PoliscoreDatasetIF dataset) {
//		openstates.syncS3LegislatorImages(dataset);
		stateImageFetcher.syncS3LegislatorImages(dataset);
	}
	
	@Override
	@SneakyThrows
	public void syncS3BillText(PoliscoreDatasetIF dataset) {
		dataset.optimizeExists(s3, BillText.class);
		
		int count = 0;
		
		for (val bill : dataset.query(Bill.class)) {
			val legiBill = legiscan.getBill(bill.getLegiscanId());
			if (legiBill.getTexts().size() == 0) continue;
			
			// TODO : This won't allow for text updates or amendments
			if (s3.exists(BillText.generateId(bill.getId()), BillText.class)) continue;
			
			var metadata = legiBill.getTexts().stream()
				    .max(Comparator.comparing(
				        LegiscanTextMetadataView::getDate,
				        Comparator.nullsFirst(Comparator.naturalOrder())
				    )).get();
			
			val doc = legiscan.getBillText(metadata.getDocId());
			
			String text;
			if (doc.getMime().equals(LegiscanMimeType.PDF)) {
				byte[] pdfBytes = Base64.getDecoder().decode(doc.getDoc());
				
				try (PDDocument document = Loader.loadPDF(pdfBytes)) {
		            PDFTextStripper stripper = new PDFTextStripper();
		            text = stripper.getText(document);
		        }
			} else if (doc.getMime().equals(LegiscanMimeType.RICH_TEXT_FORMAT) || doc.getMime().equals(LegiscanMimeType.HTML)) {
				byte[] pdfBytes = Base64.getDecoder().decode(doc.getDoc());

				text = new String(pdfBytes);
			} else {
				throw new UnsupportedOperationException("Unsupported bill text MIME type [" + doc.getMime().name() + "]");
			}
			
			if (StringUtils.isBlank(text)) {
				logger.error("Bill text was blank for " + bill.getId() + ". Skipping s3 upload to allow further processing, but you might want to look into this when you get a chance.");
				continue;
			}
			
			BillText bt = BillText.factoryFromText(bill.getId(), text, doc.getDate());
			s3.put(bt);
			
			count++;
		}
		
		dataset.clearExistsOptimize(s3, BillText.class);
		
		Log.info("Uploaded " + count + " new bill texts to s3 from Legiscan provider.");
	}

}
