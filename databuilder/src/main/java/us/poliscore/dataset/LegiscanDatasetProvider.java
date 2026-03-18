package us.poliscore.dataset;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
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
import us.poliscore.legiscan.view.LegiscanBillTextView;
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
import us.poliscore.legiscan.view.LegiscanTextType;
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
import us.poliscore.model.bill.BillTextFormat;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislativeTerm;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillCosponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillVote;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.storage.LocalCachedS3Service;

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
	
	@Inject private LocalCachedS3Service s3;
	
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
	    }

	    for (var person : cached.getPeople().values()) {
	        importLegislator(person, regularDataset);
	    }

	    if (regularDataset == dataset) {
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
		
		val originatingChamber = resolveOriginatingChamber(view);
		val introducedDate = resolveIntroducedDate(view);
		val lastActionDate = resolveLastActionDate(view);
		
		if (originatingChamber.isEmpty() || introducedDate.isEmpty() || lastActionDate.isEmpty()) {
			logger.warn("Legiscan bill " + view.getBillId() + " did not have any history and thus cannot be imported.");
			return;
		}
			
		bill.setOriginatingChamber(originatingChamber.get());
		
		if (dataset.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
    		bill.setType(toCongressionalBillType(view).name());
    	else
    		bill.setType(getChamberCode(bill.getOriginatingChamber()) + view.getBillType().getCode());
		
		bill.setId(Bill.generateId(dataset.getNamespace(), dataset.getSession().getCode(), bill.getType(), bill.getNumber()));
		
		bill.setName(view.getTitle());
    	bill.setStatus(buildStatus(view, regularDataset.getSession()));
    	bill.setIntroducedDate(introducedDate.get());
    	bill.setSponsor(convertSponsor(view.getSponsors().getFirst(), regularDataset));
    	if (view.getSponsors().size() > 1)
    		bill.setCosponsors(view.getSponsors().subList(1, view.getSponsors().size()).stream().map(s -> convertSponsor(s, regularDataset)).collect(Collectors.toList()));
    	bill.setLastActionDate(lastActionDate.get());
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

	protected Optional<LegislativeChamber> resolveOriginatingChamber(LegiscanBillView view) {
		if (StringUtils.isNotBlank(view.getBillNumber()) && Character.toUpperCase(view.getBillNumber().charAt(0)) == 'J') {
			return Optional.of(LegislativeChamber.JOINT);
		}

		Optional<LegiscanChamber> legiscanChamber = resolveLegiscanChamber(view);
		if (legiscanChamber.isPresent()) {
			return Optional.of(LegislativeChamber.fromLegiscanChamber(legiscanChamber.get()));
		}
		
		return Optional.empty();
	}

	protected static Optional<LegiscanChamber> resolveLegiscanChamber(LegiscanBillView view) {
		if (view.getHistory() != null && !view.getHistory().isEmpty() && view.getHistory().getFirst().getChamber() != null) {
			return Optional.of(view.getHistory().getFirst().getChamber());
		}

		for (String chamberCode : java.util.Arrays.asList(view.getBody(), view.getCurrentBody())) {
			if (StringUtils.isBlank(chamberCode)) {
				continue;
			}

			try {
				return Optional.of(LegiscanChamber.fromCode(chamberCode));
			}
			catch (IllegalArgumentException ignored) { }
		}

		if (StringUtils.isNotBlank(view.getBillNumber())) {
			char prefix = Character.toUpperCase(view.getBillNumber().charAt(0));
			if (prefix == 'H') {
				return Optional.of(LegiscanChamber.HOUSE);
			}
			if (prefix == 'S') {
				return Optional.of(LegiscanChamber.SENATE);
			}
			if (prefix == 'J') {
				return Optional.of(LegiscanChamber.NOT_APPLICABLE);
			}
		}

		return Optional.empty();
	}

	protected Optional<LocalDate> resolveIntroducedDate(LegiscanBillView view) {
		if (view.getHistory() != null && !view.getHistory().isEmpty() && view.getHistory().getFirst().getDate() != null) {
			return Optional.of(view.getHistory().getFirst().getDate());
		}

		return collectBillDates(view).stream().min(LocalDate::compareTo);
	}

	protected Optional<LocalDate> resolveLastActionDate(LegiscanBillView view) {
		if (view.getHistory() != null && !view.getHistory().isEmpty() && view.getHistory().getLast().getDate() != null) {
			return Optional.of(view.getHistory().getLast().getDate());
		}

		return collectBillDates(view).stream().max(LocalDate::compareTo);
	}

	protected List<LocalDate> collectBillDates(LegiscanBillView view) {
		List<LocalDate> dates = new ArrayList<>();

		if (view.getStatusDate() != null) {
			dates.add(view.getStatusDate());
		}

		if (view.getTexts() != null) {
			dates.addAll(view.getTexts().stream().map(LegiscanTextMetadataView::getDate).filter(Objects::nonNull).toList());
		}

		if (view.getProgress() != null) {
			dates.addAll(view.getProgress().stream().map(p -> p.getDate()).filter(Objects::nonNull).toList());
		}

		if (view.getVotes() != null) {
			dates.addAll(view.getVotes().stream().map(v -> v.getDate()).filter(Objects::nonNull).toList());
		}

		if (view.getAmendments() != null) {
			dates.addAll(view.getAmendments().stream().map(a -> a.getDate()).filter(Objects::nonNull).toList());
		}

		return dates;
	}
	
	public static CongressionalBillType toCongressionalBillType(LegiscanBillView bill) {
	    if (bill == null || bill.getBillTypeCode() == null) {
	        throw new IllegalArgumentException("Bill or billTypeCode is missing");
	    }

	    String code = bill.getBillTypeCode();
	    LegiscanChamber chamber = resolveLegiscanChamber(bill)
	    		.orElseThrow(() -> new IllegalArgumentException("Bill chamber could not be determined"));

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
			legId = Legislator.generateId(regularDataset.getNamespace(), regularDataset.getRegularSession().getCode(), view.getBioguideId());
		else
			legId = Legislator.generateId(regularDataset.getNamespace(), regularDataset.getRegularSession().getCode(), String.valueOf(view.getPeopleId()));
		
		var leg = regularDataset.get(legId, Legislator.class).get();
		
		var sponsor = new BillSponsor(legId, leg.getName());
		sponsor.setParty(leg.getParty());
		return sponsor;
	}

	protected BillStatus buildStatus(LegiscanBillView view, LegislativeSession session) {
	    BillStatus status = new BillStatus();
	    status.setSourceStatus(view.getStatus().getCode());

	    final LegislativeChamber chamber = resolveOriginatingChamber(view).orElseThrow();
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
			leg = regularDataset.get(Legislator.generateId(regularDataset.getNamespace(), regularDataset.getRegularSession().getCode(), String.valueOf(vote.getPeopleId())), Legislator.class).orElseThrow();
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
//	    if (view.getPartyId() == 0) return; // Kansas allows committees to sponsor bills. And so you have committees being added as "legiscan people" where most of the fields are blank. We can't handle that data yet it's way too different.

	    val leg = new Legislator();
	    leg.setLegiscanId(view.getPeopleId());
	    
	    String legId;
		if (regularDataset.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			legId = Legislator.generateId(regularDataset.getNamespace(), regularDataset.getRegularSession().getCode(), view.getBioguideId());
		else
			legId = Legislator.generateId(regularDataset.getNamespace(), regularDataset.getRegularSession().getCode(), String.valueOf(view.getPeopleId()));
		leg.setId(legId);
		
		if (view.getBio() != null && view.getBio().getLinks() != null && view.getBio().getLinks().getOfficial() != null)
			leg.setOfficialUrl(view.getBio().getLinks().getOfficial().get("website"));
		
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
	    
		val existing = regularDataset.get(legId, Legislator.class);
		if (existing.isPresent()) {
			val existingLeg = existing.get();
			
			if (StringUtils.isBlank(existingLeg.getOfficialUrl()))
				existingLeg.setOfficialUrl(leg.getOfficialUrl());
			
			existingLeg.getTerms().addAll(leg.getTerms());
			return;
		}
		
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
		
		int uploadCount = 0;
		int migratedCount = 0;
		
		for (val bill : dataset.query(Bill.class)) {
			val legiBill = legiscan.getBill(bill.getLegiscanId());
			if (legiBill.getTexts().size() == 0) continue;
			
			List<BillText> versionedBillTexts = legiBill.getTexts().stream()
					.sorted(Comparator.comparing(LegiscanTextMetadataView::getDate, Comparator.nullsFirst(Comparator.naturalOrder()))
							.thenComparing(LegiscanTextMetadataView::getDocId, Comparator.nullsFirst(Comparator.naturalOrder())))
					.map(metadata -> fetchBillTextVersion(bill, metadata))
					.filter(Objects::nonNull)
					.collect(Collectors.toList());
			
			for (val billText : versionedBillTexts) {
				if (upsertBillText(billText)) {
					uploadCount++;
				}
			}
			
			if (!versionedBillTexts.isEmpty() && migrateLegacyBillText(bill, versionedBillTexts)) {
				migratedCount++;
			}
		}
		
		dataset.clearExistsOptimize(s3, BillText.class);
		
		Log.info("Uploaded " + uploadCount + " versioned bill texts to s3 from Legiscan provider and migrated " + migratedCount + " legacy bill texts.");
	}
	
	@SneakyThrows
	protected BillText fetchBillTextVersion(Bill bill, LegiscanTextMetadataView metadata) {
		val doc = legiscan.getBillText(metadata.getDocId());
		String text = extractBillText(doc);
		
		if (StringUtils.isBlank(text)) {
			logger.error("Bill text was blank for " + bill.getId() + " version " + buildBillTextVersion(metadata) + ". Skipping s3 upload to allow further processing, but you might want to look into this when you get a chance.");
			return null;
		}
		
		return BillText.factory(bill.getId(), text, metadata.getDate(), buildBillTextVersion(metadata), getBillTextFormat(doc));
	}
	
	@SneakyThrows
	protected String extractBillText(LegiscanBillTextView doc) {
		if (doc.getMime().equals(LegiscanMimeType.PDF)) {
			byte[] pdfBytes = Base64.getDecoder().decode(doc.getDoc());
			
			try (PDDocument document = Loader.loadPDF(pdfBytes)) {
	            PDFTextStripper stripper = new PDFTextStripper();
	            return stripper.getText(document);
	        }
		}
		
		if (doc.getMime().equals(LegiscanMimeType.RICH_TEXT_FORMAT) || doc.getMime().equals(LegiscanMimeType.HTML)) {
			byte[] textBytes = Base64.getDecoder().decode(doc.getDoc());
			return new String(textBytes);
		}
		
		throw new UnsupportedOperationException("Unsupported bill text MIME type [" + doc.getMime().name() + "]");
	}
	
	protected BillTextFormat getBillTextFormat(LegiscanBillTextView doc) {
		if (doc.getMime().equals(LegiscanMimeType.HTML)) {
			return BillTextFormat.HTML;
		}
		
		if (doc.getMime().equals(LegiscanMimeType.RICH_TEXT_FORMAT)) {
			return BillTextFormat.RTF;
		}
		
		return BillTextFormat.TEXT;
	}
	
	protected String buildBillTextVersion(LegiscanTextMetadataView metadata) {
		LegiscanTextType type = null;
		if (metadata.getTypeId() != null) {
			try {
				type = LegiscanTextType.fromValue(metadata.getTypeId());
			}
			catch (IllegalArgumentException ignored) { }
		}
		
		String typeToken = type != null
				? type.name()
				: (StringUtils.isBlank(metadata.getTypeCode()) ? "DOC" : metadata.getTypeCode().trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_"));
		return typeToken + "-" + metadata.getDocId();
	}
	
	protected boolean upsertBillText(BillText candidate) {
		if (s3.exists(candidate.getId(), BillText.class))
			return false;
		
		s3.put(candidate);
		return true;
	}
	
	protected boolean migrateLegacyBillText(Bill bill, List<BillText> versionedBillTexts) {
		val legacyId = BillText.generateId(bill.getId());
		val legacy = s3.get(legacyId, BillText.class).orElse(null);
		if (legacy == null) {
			return false;
		}
		
		s3.delete(legacyId, BillText.class);
		Log.info("Migrated legacy state bill text " + legacyId + " to versioned key.");
		return true;
	}

}
