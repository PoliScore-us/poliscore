package us.poliscore.dataset;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.PoliscoreUtil;
import us.poliscore.dataset.augmentation.PoliscoreDatasetAugmentor;
import us.poliscore.legiscan.cache.CachedLegiscanDatasetResult;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.legiscan.view.*;
import us.poliscore.model.BuildReport;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillVote;
import us.poliscore.service.SessionInfoService;

class LegiscanImportToleranceTest {

	private List<LegislativeSession> sessions;
	private CachedLegiscanDatasetResult cached;
	private LegiscanDatasetProvider provider;
	private final DeploymentConfig config = new DeploymentConfig(LegislativeNamespace.US_TEXAS, 2028, 1f, true, false);
	private final BuildReport report = new BuildReport();

	@BeforeEach
	void setUp() {
		sessions = SessionInfoService.getSessions();
		SessionInfoService.buildSessions(List.of());
		var view = new LegiscanDatasetView();
		view.setStateId(43);
		view.setSessionId(2278);
		view.setYearStart(2027);
		view.setYearEnd(2028);
		cached = new CachedLegiscanDatasetResult(null, view, PoliscoreUtil.getObjectMapper());
		provider = new LegiscanDatasetProvider();
		provider.legiscan = new CachedLegiscanService("test", PoliscoreUtil.getObjectMapper(), null, 1) {
			@Override public List<LegiscanDatasetView> getDatasetList(LegiscanState state, Integer year) { return List.of(view); }
			@Override public CachedLegiscanDatasetResult cacheDataset(LegiscanDatasetView input) { return cached; }
		};
		provider.augmentor = new PoliscoreDatasetAugmentor() {
			@Override public void augmentLegislators(PoliscoreDataset input) { }
		};
	}

	@AfterEach
	void restoreSessions() throws Exception {
		var field = SessionInfoService.class.getDeclaredField("cachedSessions");
		field.setAccessible(true);
		field.set(null, sessions);
	}

	@Test
	void isolatedBadMembersBillsAndVotesDoNotDiscardHealthyRecords() {
		person(10, LegiscanParty.REPUBLICAN.getValue());
		person(20, LegiscanParty.DEMOCRAT.getValue());
		person(30, 0);
		bill(100, 10);
		bill(101, 10).setStatusId(999);
		bill(102, 30); // An unresolved primary sponsor affects this bill only.
		bill(103, 20);
		rollCall(500, 100, vote(30, 1), vote(20, 0), vote(10, 1), vote(20, 2));
		rollCall(501, 100).setVotes(null);
		rollCall(502, 103, vote(20, 1));
		rollCall(503, 101, vote(10, 1)); // Deliberately skipped bill.

		var dataset = provider.importDataset(config, report);

		assertEquals(2, dataset.query(Legislator.class).size());
		assertEquals(2, dataset.query(Bill.class).size());
		assertTrue(dataset.exists("BIL/us/tx/2278/sb/100", Bill.class));
		assertFalse(dataset.exists("BIL/us/tx/2278/sb/102", Bill.class));
		assertEquals(1, dataset.get("LEG/us/tx/2278/10", Legislator.class).orElseThrow().getInteractionsAll().stream()
				.filter(interaction -> interaction instanceof LegislatorBillVote).count());
		assertEquals(2, dataset.get("LEG/us/tx/2278/20", Legislator.class).orElseThrow().getInteractionsAll().stream()
				.filter(interaction -> interaction instanceof LegislatorBillVote).count());
		assertEquals(6, report.getImportWarnings().size());
		assertFalse(report.hasFatal());
		assertFalse(report.hasRecovered());
		assertTrue(report.toString().contains("rollCallId=500, peopleId=30"));
		assertTrue(report.toString().contains("Skipped bill"));

		// When the upstream record is repaired, the next import restores it under the original key.
		cached.getPeople().get(30).setPartyId(LegiscanParty.DEMOCRAT.getValue());
		var repaired = provider.importDataset(config, new BuildReport());
		assertTrue(repaired.exists("LEG/us/tx/2278/30", Legislator.class));
		assertTrue(repaired.exists("BIL/us/tx/2278/sb/102", Bill.class));
	}

	@Test
	void everyBillFailingStillStopsAnUnusableImport() {
		person(10, LegiscanParty.REPUBLICAN.getValue());
		bill(100, 10).setStatusId(999);

		var failure = assertThrows(IllegalStateException.class, () -> provider.importDataset(config, report));

		assertTrue(failure.getMessage().contains("no usable bills"));
		assertTrue(report.hasBlockingFatal());
		assertEquals(1, failure.getSuppressed().length);
	}

	@Test
	void entirelyUnusableOptionalRosterRemainsPendingBeforeRegistration() {
		person(30, 0);
		config.setOptional(true);

		assertThrows(DatasetUnavailableException.class, () -> provider.importDataset(config, report));
		assertTrue(SessionInfoService.getSessions().isEmpty());
		assertFalse(report.hasFatal());
	}

	private void person(int id, int party) {
		var person = new LegiscanPeopleView();
		person.setPeopleId(id);
		person.setStateId(43);
		person.setName("Test Member " + id);
		person.setFirstName("Test");
		person.setLastName("Member " + id);
		person.setPartyId(party);
		person.setRoleId(LegiscanRole.SENATOR.getValue());
		person.setDistrict("SD-001");
		cached.getPeople().put(id, person);
	}

	private LegiscanBillView bill(int id, int sponsorId) {
		var bill = new LegiscanBillView();
		bill.setBillId(id);
		bill.setBillNumber("SB" + id);
		bill.setBody("S");
		bill.setBillTypeId(LegiscanBillType.BILL.getValue());
		bill.setTitle("Test bill " + id);
		bill.setStatusId(LegiscanStatus.INTRODUCED.getValue());
		bill.setStatusDate(LocalDate.of(2027, 1, 15));
		var sponsor = new LegiscanSponsorView();
		sponsor.setPeopleId(sponsorId);
		bill.setSponsors(List.of(sponsor));
		cached.getBills().put(id, bill);
		return bill;
	}

	private LegiscanRollCallView rollCall(int id, int billId, LegiscanVoteDetailView... votes) {
		var rollCall = new LegiscanRollCallView();
		rollCall.setRollCallId(id);
		rollCall.setBillId(billId);
		rollCall.setDate(LocalDate.of(2027, 2, 1));
		rollCall.setDescription("On passage");
		rollCall.setVotes(List.of(votes));
		cached.getVotes().put(id, rollCall);
		return rollCall;
	}

	private LegiscanVoteDetailView vote(int personId, int status) {
		var vote = new LegiscanVoteDetailView();
		vote.setPeopleId(personId);
		vote.setVoteId(status);
		return vote;
	}
}
