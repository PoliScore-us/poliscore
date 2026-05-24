package us.poliscore.dataset;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
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
import us.poliscore.PoliscoreUtil;
import us.poliscore.dataset.augmentation.PoliscoreDatasetAugmentor;
import us.poliscore.entrypoint.GPOBulkBillTextFetcher;
import us.poliscore.images.CongressionalLegislatorImageFetcher;
import us.poliscore.images.StateLegislatorImageFetcher;
import us.poliscore.legiscan.LegiscanVoteConverter;
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
import us.poliscore.model.CongressionalSession;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.Bill.BillSponsor;
import us.poliscore.model.bill.BillStatus;
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.BillTextFormat;
import us.poliscore.model.bill.BillTextPublishVersion;
import us.poliscore.parsing.PDFToText;
import us.poliscore.model.bill.CongressionalBillType;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislativeTerm;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillCosponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillVote;
import us.poliscore.service.BillService;
import us.poliscore.service.CongressionalBillTextXmlService;
import us.poliscore.service.LegislatorService;
import us.poliscore.service.SessionInfoService;
import us.poliscore.service.storage.LocalCachedS3Service;
import us.poliscore.view.USCLegislatorView;

@ApplicationScoped
@Named("legiscan")
@DefaultBean
public class LegiscanDatasetProvider implements DatasetProvider {

	private static final Logger logger = LoggerFactory.getLogger(LegiscanDatasetProvider.class);
	private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
	private static final Pattern HTML_CHARSET = Pattern.compile("(?is)<meta\\b[^>]*charset\\s*=\\s*[\"']?([^\\s\"'>;]+)");
	private static final Pattern RTF_ANSI_CODE_PAGE = Pattern.compile("\\\\ansicpg(\\d+)");
	private static final Pattern UNAVAILABLE_FORMAT_NOTICE = Pattern.compile("\\b(html|word|rtf|text|pdf)\\b.{0,100}\\b(version|versions|copy|copies|document|documents)\\b.{0,100}\\b(not\\s+available|unavailable|not\\s+provided|cannot\\s+be\\s+displayed)\\b|\\b(not\\s+available|unavailable|not\\s+provided|cannot\\s+be\\s+displayed)\\b.{0,100}\\b(html|word|rtf|text|pdf)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern FORMAT_REDIRECT_NOTICE = Pattern.compile("\\b(see|view|open|refer\\s+to|consult|download)\\b.{0,100}\\b(pdf|word|html|website|site|link|document)\\b|\\b(pdf|word|html)\\b.{0,100}\\b(content\\s+of\\s+(this\\s+)?bill|bill\\s+content|full\\s+text)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern LEGISLATIVE_TEXT_MARKER = Pattern.compile("\\b(be\\s+it\\s+enacted|resolved,|section\\s+\\d+|sec\\.\\s+\\d+|article\\s+[ivxlcdm0-9]+|chapter\\s+\\d+|subchapter\\s+[a-z0-9]+|amend(ed|ing)|appropriat(e|ion)|whereas)\\b", Pattern.CASE_INSENSITIVE);
	
//	@Inject
//	private SecretService secret;
	
	@Inject
	protected LegislatorService lService;
	@Inject
	protected BillService billService;
	@Inject
	protected CongressionalBillTextXmlService congressionalXml;

	private final PDFToText pdfToText = new PDFToText();
	
	@Inject protected PoliscoreDatasetAugmentor augmentor;
	@Inject protected StateLegislatorImageFetcher stateImageFetcher;
	@Inject protected CongressionalLegislatorImageFetcher congressionalImageFetcher;
	
	@Inject private LocalCachedS3Service s3;
	
	@Inject
	protected CachedLegiscanService legiscan;
	
	private Map<Integer, Bill> legiscanIdToBill = new HashMap<Integer, Bill>();
	private Map<Integer, Legislator> legiscanIdToLegislator = new HashMap<Integer, Legislator>();
	private List<USCLegislatorView> congressionalLegislatorLookup;
	
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
	    	try {
	    		importLegislator(person, regularDataset);
	    	} catch (RuntimeException e) {
				logger.error("Error occurred while importing legislator. session={}, peopleId={}, bioguideId={}, name={}", session.getDescription(), person.getPeopleId(), person.getBioguideId(), person.getName(), e);
	    	}
	    }

	    if (regularDataset == dataset) {
	        augmentor.augmentLegislators(dataset);
	    }

	    for (var bill : cached.getBills().values()) {
	    	try {
	    		importBill(bill, dataset, regularDataset);
	    	} catch (RuntimeException e) {
	    		logger.error("Error occurred while importing bill. session={}, legiscanId={}, billType={}, billNumber={}", session.getDescription(), bill.getBillId(), bill.getBillType(), bill.getBillNumber(), e);
	    	}
	    }

	    for (var vote : cached.getVotes().values()) {
	    	try {
	    		importRollCall(vote, dataset, regularDataset);
	    	} catch (RuntimeException e) {
	    		logger.error("Error occurred while importing roll call. session={}, legiscanId={}", session.getDescription(), vote.getRollCallId(), e);
	    	}
	    }

	    return dataset;
	}
	
	public static LegiscanState namespaceToState(LegislativeNamespace namespace) {
		if (namespace.equals(LegislativeNamespace.US_CONGRESS))
			return LegiscanState.CONGRESS;
		else
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
	
	protected static String getChamberCode(LegislativeChamber chamber) {
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
		
		if (!populate(bill, view, dataset.getSession())) {
			logger.warn("Legiscan bill " + view.getBillId() + " did not have any history and thus cannot be imported.");
			return;
		}
		
    	bill.setSponsor(convertSponsor(view.getSponsors().getFirst(), regularDataset));
    	if (bill.getSponsor() == null)
    		throw new IllegalStateException("Primary sponsor is required for bill " + view.getBillId() + " but legislator with people id " + view.getSponsors().getFirst().getPeopleId() + " could not be resolved.");
    	if (view.getSponsors().size() > 1)
    		bill.setCosponsors(view.getSponsors().subList(1, view.getSponsors().size()).stream().map(s -> convertSponsor(s, regularDataset)).filter(Objects::nonNull).collect(Collectors.toList()));
    	
    	
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
    	
    	legiscanIdToBill.put(bill.getLegiscanId(), bill);
    	dataset.put(bill);
	}
	
	public static boolean populate(Bill bill, LegiscanBillView view, LegislativeSession session) {
		val originatingChamber = resolveOriginatingChamber(view);
		val introducedDate = resolveIntroducedDate(view);
		val lastActionDate = resolveLastActionDate(view);
		
		if (originatingChamber.isEmpty() || introducedDate.isEmpty() || lastActionDate.isEmpty()) {
			logger.warn("Legiscan bill " + view.getBillId() + " did not have any history and thus cannot be imported.");
			return false;
		}
		
		if (session == null) {
			session = SessionInfoService.lookupSession(bill.getNamespace(), bill.getSessionCode());
		} else {
			bill.setNumber(Integer.parseInt(view.getBillNumber().replaceAll("[^\\d]", "")));
			bill.setOriginatingChamber(originatingChamber.get());
	
			if (session.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
	    		bill.setType(toCongressionalBillType(view).name());
	    	else
	    		bill.setType(getChamberCode(bill.getOriginatingChamber()) + view.getBillType().getCode());
			
			bill.setId(Bill.generateId(session.getNamespace(), session.getCode(), bill.getType(), bill.getNumber()));
		}
		
		bill.setName(view.getTitle());
		bill.setStatus(buildStatus(view, session));
    	bill.setIntroducedDate(introducedDate.get());
    	bill.setLastActionDate(lastActionDate.get());
    	bill.setLegiscanId(view.getBillId());
    	bill.setOfficialUrl(view.getStateLink());
    	bill.setTexts(buildBillTextMetadata(bill.getId(), view));
    	
    	return true;
	}

	protected static Optional<LegislativeChamber> resolveOriginatingChamber(LegiscanBillView view) {
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

	protected static Optional<LocalDate> resolveIntroducedDate(LegiscanBillView view) {
		if (view.getHistory() != null && !view.getHistory().isEmpty() && view.getHistory().getFirst().getDate() != null) {
			return Optional.of(view.getHistory().getFirst().getDate());
		}

		return collectBillDates(view).stream().min(LocalDate::compareTo);
	}

	protected static Optional<LocalDate> resolveLastActionDate(LegiscanBillView view) {
		if (view.getHistory() != null && !view.getHistory().isEmpty() && view.getHistory().getLast().getDate() != null) {
			return Optional.of(view.getHistory().getLast().getDate());
		}

		return collectBillDates(view).stream().max(LocalDate::compareTo);
	}

	protected static List<LocalDate> collectBillDates(LegiscanBillView view) {
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
		Legislator leg = legiscanIdToLegislator.get(view.getPeopleId());
		if (leg == null) {
			logger.warn("Skipping sponsor import because we could not find legislator with people id {}", view.getPeopleId());
			return null;
		}
		
		var sponsor = new BillSponsor(leg.getId(), leg.getName());
		sponsor.setParty(leg.getParty());
		return sponsor;
	}

	protected static BillStatus buildStatus(LegiscanBillView view, LegislativeSession session) {
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
		Legislator leg = legiscanIdToLegislator.get(vote.getPeopleId());
		if (leg == null) {
			Log.warn("Could not find legislator with people id " + vote.getPeopleId());
			return;
		}
		
		Bill bill = legiscanIdToBill.get(rollCall.getBillId());
		if (bill == null) {
			Log.warn("Could not find bill with id " + rollCall.getBillId());
			return;
		}
		
		LegislatorBillVote interaction = LegiscanVoteConverter.convert(rollCall, vote, leg, bill);

		if (interaction != null)
			leg.addBillInteraction(interaction);
	}
	
//	protected Legislator legislatorByPeopleId(PoliscoreDataset regularDataset, Integer peopleId) {
//		return regularDataset.query(Legislator.class).stream().filter(l -> l.getLegiscanId().equals(peopleId)).findFirst().orElseThrow();
//	}
	
	protected void importLegislator(LegiscanPeopleView view, PoliscoreDataset regularDataset) {
	    if (view == null || StringUtils.isBlank(view.getName())) return;
//	    if (view.getPartyId() == 0) return; // Kansas allows committees to sponsor bills. And so you have committees being added as "legiscan people" where most of the fields are blank. We can't handle that data yet it's way too different.

	    val leg = new Legislator();
	    leg.setLegiscanId(view.getPeopleId());
	    
	    String legId;
		if (regularDataset.getNamespace().equals(LegislativeNamespace.US_CONGRESS)) {
			legId = Legislator.generateId(regularDataset.getNamespace(), regularDataset.getRegularSession().getCode(), resolveCongressionalBioguideId(view, regularDataset.getRegularSession()));
		} else
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
	    
	    if (LegislativeNamespace.US_CONGRESS.equals(regularDataset.getNamespace())) {
	    	var split = view.getDistrict().split("-");
	    	term.setState(LegiscanState.fromAbbreviation(split[1]));
	    	if (split.length > 2) term.setDistrict(split[2]);
	    } else {
		    term.setState(view.getState());
		    term.setDistrict(StringUtils.isBlank(view.getDistrict()) ? null : view.getDistrict());
	    }
	    
	    term.setChamber(LegislativeChamber.fromLegiscanRole(view.getRole()));
	    leg.getTerms().add(term);

		Persistable.validate(leg);
		
		val existing = regularDataset.get(legId, Legislator.class);
		if (existing.isPresent()) {
			val existingLeg = existing.get();
			
			if (StringUtils.isBlank(existingLeg.getOfficialUrl()))
				existingLeg.setOfficialUrl(leg.getOfficialUrl());
			
			existingLeg.getTerms().addAll(leg.getTerms());
			return;
		}
		
		legiscanIdToLegislator.put(leg.getLegiscanId(), leg);
		regularDataset.put(leg);
	}

	protected String resolveCongressionalBioguideId(LegiscanPeopleView view, LegislativeSession session) {
		if (StringUtils.isNotBlank(view.getBioguideId())) return view.getBioguideId();

		val matches = congressionalLegislators().stream()
				.filter(usc -> congressionalLegislatorMatches(view, session, usc))
				.collect(Collectors.toList());

		if (matches.size() == 1) {
			logger.info("Resolved missing congressional bioguide id for LegiScan people id {} ({}) as {}", view.getPeopleId(), view.getName(), matches.getFirst().getId().getBioguide());
			return matches.getFirst().getId().getBioguide();
		}

		if (matches.size() > 1) {
			throw new IllegalArgumentException("Could not resolve congressional bioguide id for LegiScan people id " + view.getPeopleId() + " (" + view.getName() + "): multiple USC legislators matched.");
		}

		throw new IllegalArgumentException("Could not resolve congressional bioguide id for LegiScan people id " + view.getPeopleId() + " (" + view.getName() + ").");
	}

	private boolean congressionalLegislatorMatches(LegiscanPeopleView legiscan, LegislativeSession session, USCLegislatorView usc) {
		if (usc.getId() == null || StringUtils.isBlank(usc.getId().getBioguide())) return false;
		if (usc.getName() == null || !equalsIgnoreCase(usc.getName().convert().getOfficial_full(), legiscan.getName())) return false;

		val chamber = LegislativeChamber.fromLegiscanRole(legiscan.getRole());
		val districtParts = legiscan.getDistrict() == null ? new String[0] : legiscan.getDistrict().split("-");
		val legiscanState = districtParts.length > 1 ? districtParts[1] : null;
		val legiscanDistrict = districtParts.length > 2 ? districtParts[2] : null;

		return usc.getTerms() != null && usc.getTerms().stream().anyMatch(term -> {
			if (!term.getStart().isBefore(session.getEndDate()) || !term.getEnd().isAfter(session.getStartDate())) return false;
			if (!equalsIgnoreCase(term.getState(), legiscanState)) return false;
			if (chamber == LegislativeChamber.LOWER && !equalsIgnoreCase("rep", term.getType())) return false;
			if (chamber == LegislativeChamber.UPPER && !equalsIgnoreCase("sen", term.getType())) return false;
			if (chamber == LegislativeChamber.LOWER) {
				return StringUtils.equals(String.valueOf(term.getDistrict()), legiscanDistrict);
			}
			return true;
		});
	}

	private boolean equalsIgnoreCase(String a, String b) {
		if (a == null || b == null) return a == b;
		return a.equalsIgnoreCase(b);
	}

	@SneakyThrows
	private List<USCLegislatorView> congressionalLegislators() {
		if (congressionalLegislatorLookup != null) return congressionalLegislatorLookup;

		List<USCLegislatorView> views = new ArrayList<>();
		ObjectMapper mapper = PoliscoreUtil.getObjectMapper();
		for (String file : List.of("/legislators-current.json", "/legislators-historical.json")) {
			JsonNode root = mapper.readTree(LegislatorService.class.getResourceAsStream(file));
			root.elements().forEachRemaining(node -> {
				try {
					views.add(mapper.treeToValue(node, USCLegislatorView.class));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
		}

		congressionalLegislatorLookup = views;
		return congressionalLegislatorLookup;
	}


	@Override
	public void syncS3LegislatorImages(PoliscoreDatasetIF dataset) {
//		openstates.syncS3LegislatorImages(dataset);
		if (!dataset.getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			stateImageFetcher.syncS3LegislatorImages(dataset);
		else
			congressionalImageFetcher.syncS3LegislatorImages(dataset);
	}
	
	@Override
	@SneakyThrows
	public void syncS3BillText(PoliscoreDatasetIF dataset) {
		if (dataset.getCode().equals("118")) return;
		
		dataset.optimizeExists(s3, BillText.class);
		
		int uploadCount = 0;
		int migratedCount = 0;
		
			for (val bill : dataset.query(Bill.class)) {
				val legiBill = legiscan.getBill(bill.getLegiscanId());
				val existingTextMetadata = billTextMetadataSignature(bill.getTexts());
				val refreshedTextMetadata = buildBillTextMetadata(bill.getId(), legiBill);
				if (!Objects.equals(existingTextMetadata, billTextMetadataSignature(refreshedTextMetadata))) {
					bill.setLastUpdate(LocalDateTime.now());
				}
				bill.setTexts(refreshedTextMetadata);
				dataset.put(bill);
				if (legiBill.getTexts().isEmpty()) continue;
			
			val latestMetadata = legiBill.getTexts().stream().max(Comparator.comparing(LegiscanTextMetadataView::getDate, Comparator.nullsFirst(Comparator.naturalOrder())));
			if (latestMetadata.isEmpty()) continue;
			
			// Was already fetched using GPO fetcher
			if (latestMetadata.get().getDate() != null && latestMetadata.get().getDate().isBefore(LocalDate.of(2026, 3, 10))) continue;
			
			if (s3.exists(BillText.generateId(bill.getId(), buildBillTextVersion(latestMetadata.get())), BillText.class)) { continue; }
			
			// Migrate bill text from older GPO format over to the newer legiscan format. Can be removed once migrated.
			if (bill.getNamespace().equals(LegislativeNamespace.US_CONGRESS) && migrateCongressLegiscanBillTextCompatibility(bill, latestMetadata.get())) {
				continue;
			}
			
			val latestBillText = fetchBillTextVersion(bill, latestMetadata.get());
			if (latestBillText == null) continue;
			
			s3.put(latestBillText);
			uploadCount++;
			
			// Bill text is now stored by version. Remove once migrated
			if (migrateBillTextVersion(bill)) {
				migratedCount++;
			}
		}
		
		dataset.clearExistsOptimize(s3, BillText.class);
		
		Log.info("Uploaded " + uploadCount + " latest bill texts to s3 from Legiscan provider and migrated " + migratedCount + " legacy bill texts.");
	}

	protected static List<BillText> buildBillTextMetadata(String billId, LegiscanBillView view) {
		if (view == null || view.getTexts() == null || view.getTexts().isEmpty()) {
			return List.of();
		}

		return view.getTexts().stream()
				.map(metadata -> BillText.factory(
						billId,
						metadata.getDocId(),
						null,
						metadata.getDate(),
						buildBillTextVersion(metadata),
						getBillTextFormat(metadata.getMime())))
				.sorted(Comparator.comparing(BillText::getLastUpdate, Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
	}

	private static List<String> billTextMetadataSignature(Collection<BillText> texts) {
		if (texts == null || texts.isEmpty()) {
			return List.of();
		}

		return texts.stream()
				.filter(Objects::nonNull)
				.sorted(Comparator.comparing(BillText::getId, Comparator.nullsLast(Comparator.naturalOrder())))
				.map(text -> String.join("|",
						Objects.toString(text.getId(), ""),
						Objects.toString(text.getVersion(), ""),
						Objects.toString(text.getLastUpdate(), ""),
						Objects.toString(text.getFormat(), ""),
						Objects.toString(text.getLegiscanId(), "")))
				.toList();
	}
	
	protected boolean migrateCongressLegiscanBillTextCompatibility(Bill bill, LegiscanTextMetadataView metadata) {
		if (bill == null || metadata == null || StringUtils.isBlank(metadata.getStateLink())) {
			return false;
		}

		if (!bill.getNamespace().equals(LegislativeNamespace.US_CONGRESS)) {
			return false;
		}

		String legiscanVersion = buildBillTextVersion(metadata);

		try {
			String stateLink = metadata.getStateLink();
			String fileName = stateLink.substring(stateLink.lastIndexOf('/') + 1);

			if (StringUtils.isBlank(fileName) || !fileName.startsWith("BILLS-")) {
				return false;
			}

			BillTextPublishVersion gpoVersion = BillTextPublishVersion.parseFromBillTextName(fileName);
			String gpoBillTextId = BillText.generateId(bill.getId(), gpoVersion);

			BillText existing = s3.get(gpoBillTextId, BillText.class).orElse(null);
			if (existing == null) {
				return false;
			}

			BillText migrated = BillText.factory(
					bill.getId(),
					metadata.getDocId(),
					existing.getText(),
					existing.getLastUpdated(),
					legiscanVersion,
					existing.getEffectiveFormat()
			);

			s3.put(migrated);
			return true;
		} catch (Exception ex) {
			return false;
		}
	}
	
	@SneakyThrows
	protected BillText fetchBillTextVersion(Bill bill, LegiscanTextMetadataView metadata) {
		if (bill.getNamespace().equals(LegislativeNamespace.US_CONGRESS)) {
			val xmlText = congressionalXml.fetchXmlBillText(bill, metadata);
			if (xmlText.isPresent()) {
				return xmlText.get();
			}
		}

		val doc = legiscan.getBillText(metadata.getDocId());
		String text = extractBillText(doc);
		
		if (StringUtils.isBlank(text)) {
			logger.error("Bill text was blank for " + bill.getId() + " version " + buildBillTextVersion(metadata) + ". Skipping s3 upload to allow further processing, but you might want to look into this when you get a chance.");
			return null;
		}
		
		LocalDate date = Objects.requireNonNullElse(metadata.getDate(), doc.getDate());
		if (date == null && bill.getNamespace().equals(LegislativeNamespace.US_CONGRESS)) {
			date = GPOBulkBillTextFetcher.parseDate(text);
		}
		if (date == null && isIntroducedBillText(metadata, doc)) {
			date = bill.getIntroducedDate();
		}
		if (date == null) {
			throw new NullPointerException();
		}
		
		return BillText.factory(bill.getId(), doc.getDocId(), text, date, buildBillTextVersion(metadata), getBillTextFormat(doc.getMime()));
	}

	protected boolean isIntroducedBillText(LegiscanTextMetadataView metadata, LegiscanBillTextView doc) {
		if (metadata != null && metadata.getTypeId() != null) {
			try {
				if (LegiscanTextType.INTRODUCED.equals(metadata.getType())) {
					return true;
				}
			} catch (IllegalArgumentException ignored) { }
		}

		if (doc != null) {
			try {
				return LegiscanTextType.INTRODUCED.equals(doc.getType());
			} catch (IllegalArgumentException ignored) { }
		}

		return false;
	}
	
	@SneakyThrows
	protected String extractBillText(LegiscanBillTextView doc) {
		String text;
		if (doc.getMime().equals(LegiscanMimeType.PDF)) {
			byte[] pdfBytes = Base64.getDecoder().decode(doc.getDoc());
			text = pdfToText.extract(pdfBytes);
		} else if (doc.getMime().equals(LegiscanMimeType.HTML)) {
			byte[] textBytes = Base64.getDecoder().decode(doc.getDoc());
			text = decodeHtmlBillText(textBytes);
		} else if (doc.getMime().equals(LegiscanMimeType.RICH_TEXT_FORMAT)) {
			byte[] textBytes = Base64.getDecoder().decode(doc.getDoc());
			text = decodeRtfBillText(textBytes);
		} else {
			throw new UnsupportedOperationException("Unsupported bill text MIME type [" + doc.getMime().name() + "]");
		}

		return isUnsupportedBillTextPlaceholder(text) ? null : text;
	}

	protected boolean isUnsupportedBillTextPlaceholder(String text) {
		String normalized = normalizeBillTextPlaceholderNotice(text);
		if (normalized.isBlank() || normalized.length() > 1_500 || LEGISLATIVE_TEXT_MARKER.matcher(normalized).find()) {
			return false;
		}

		return UNAVAILABLE_FORMAT_NOTICE.matcher(normalized).find()
				&& FORMAT_REDIRECT_NOTICE.matcher(normalized).find();
	}

	private String normalizeBillTextPlaceholderNotice(String text) {
		if (StringUtils.isBlank(text)) {
			return "";
		}

		String value = text;
		if (looksLikeHtml(value)) {
			value = Jsoup.parse(value).text();
		}

		return value
				.replace('\u00A0', ' ')
				.replaceAll("\\s+", " ")
				.trim()
				.toLowerCase(Locale.ROOT);
	}

	private boolean looksLikeHtml(String text) {
		return Pattern.compile("(?is)<\\s*(html|head|body|p|div|span|table|tr|td|br)\\b").matcher(text).find();
	}

	private String decodeHtmlBillText(byte[] textBytes) {
		String headerText = new String(textBytes, StandardCharsets.ISO_8859_1);
		Matcher matcher = HTML_CHARSET.matcher(headerText);
		if (matcher.find()) {
			try {
				return new String(textBytes, Charset.forName(matcher.group(1).trim()));
			} catch (Exception ignored) { }
		}

		return new String(textBytes, WINDOWS_1252);
	}

	private String decodeRtfBillText(byte[] textBytes) {
		String headerText = new String(textBytes, StandardCharsets.ISO_8859_1);
		Matcher matcher = RTF_ANSI_CODE_PAGE.matcher(headerText);
		if (matcher.find()) {
			try {
				return new String(textBytes, Charset.forName("windows-" + matcher.group(1)));
			} catch (Exception ignored) { }
		}

		return new String(textBytes, WINDOWS_1252);
	}
	
	public static BillTextFormat getBillTextFormat(LegiscanMimeType mime) {
		if (mime.equals(LegiscanMimeType.HTML)) {
			return BillTextFormat.HTML;
		}
		
		if (mime.equals(LegiscanMimeType.RICH_TEXT_FORMAT)) {
			return BillTextFormat.RTF;
		}
		
		return BillTextFormat.TEXT;
	}
	
	public static String buildBillTextVersion(LegiscanTextMetadataView metadata) {
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
	
	protected boolean migrateBillTextVersion(Bill bill) {
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
