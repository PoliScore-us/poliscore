package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import us.poliscore.model.*;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislativeTerm;
import us.poliscore.legiscan.view.LegiscanState;

class LegislativeRolloverTest {
    List<LegislativeSession> previous;
    @BeforeEach void snapshot() { previous = SessionInfoService.getSessions(); }
    @AfterEach void restore() throws Exception {
        var field = SessionInfoService.class.getDeclaredField("cachedSessions"); field.setAccessible(true); field.set(null, previous);
    }
    LegislativeSession session(int number) {
        var c = CongressionalSession.of(number);
        return new LegislativeSession(true,c.getStartDate(),c.getEndDate(),""+number,LegislativeNamespace.US_CONGRESS);
    }
    @Test void congressIsComputedWithoutAnExpiryAndKeepsStorageYearsSeparateFromTerms() {
        assertEquals(120, CongressionalSession.fromYear(2027).getNumber());
        assertEquals(120, CongressionalSession.fromYear(2028).getNumber());
        assertEquals(121, CongressionalSession.fromYear(2029).getNumber());
        assertEquals(LocalDate.of(2028,12,31), CongressionalSession.of(120).getEndDate());
        assertEquals(LocalDate.of(2027,1,3), CongressionalSession.of(120).getTermStartDate());
        assertEquals(LocalDate.of(2029,1,3), CongressionalSession.of(120).getTermEndDate());
        assertEquals(2028, CongressionalSession.of(120).getEndYear());
        assertThrows(IllegalArgumentException.class, () -> CongressionalSession.fromYear(1788));
    }
    @Test void defaultChangesOnJanuaryThirdAndFallsBackWhenUpstreamIsLate() {
        SessionInfoService.buildSessions(List.of());
        SessionInfoService.registerSessions(List.of(session(119)));
        assertEquals("119", SessionInfoService.latestAvailableSession(LegislativeNamespace.US_CONGRESS,LocalDate.of(2027,1,5)).getCode());
        assertThrows(NoSuchElementException.class, () -> SessionInfoService.resolveSession(LegislativeNamespace.US_CONGRESS,2028));
        SessionInfoService.registerSessions(List.of(session(120)));
        assertEquals("119", SessionInfoService.latestAvailableSession(LegislativeNamespace.US_CONGRESS,LocalDate.of(2027,1,1)).getCode());
        assertEquals("119", SessionInfoService.latestAvailableSession(LegislativeNamespace.US_CONGRESS,LocalDate.of(2027,1,2)).getCode());
        assertEquals("120", SessionInfoService.latestAvailableSession(LegislativeNamespace.US_CONGRESS,LocalDate.of(2027,1,3)).getCode());
        assertEquals("119", SessionInfoService.lookupRegularSession(LegislativeNamespace.US_CONGRESS,2026).getCode());
    }
    @Test void stateDefaultsHonorSourcedActivationDates() {
        SessionInfoService.buildSessions(List.of());
        var old = new LegislativeSession(true,LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),"co-old",LegislativeNamespace.US_COLORADO);
        var next = new LegislativeSession(true,LocalDate.of(2027,1,1),LocalDate.of(2027,12,31),"co-next",LegislativeNamespace.US_COLORADO);
        next.setAvailableFrom("2027-01-11");
        SessionInfoService.registerSessions(List.of(old,next));
        assertEquals("co-old",SessionInfoService.latestAvailableSession(LegislativeNamespace.US_COLORADO,LocalDate.of(2027,1,10)).getCode());
        assertEquals("co-next",SessionInfoService.latestAvailableSession(LegislativeNamespace.US_COLORADO,LocalDate.of(2027,1,11)).getCode());
    }
    @Test void texasPrefilingActivatesPagesWithoutExpandingSessionMembership() {
        var next = SessionInfoService.lookupRegularSession(LegislativeNamespace.US_TEXAS, 2028);
        assertEquals("2278", next.getCode());
        assertEquals(LocalDate.of(2026,11,9), next.getActivationDate());
        assertEquals(LocalDate.of(2027,1,1), next.getTermStartDate());
        assertEquals("2160", SessionInfoService.latestAvailableSession(LegislativeNamespace.US_TEXAS,LocalDate.of(2026,11,8)).getCode());
        assertEquals(next, SessionInfoService.latestAvailableSession(LegislativeNamespace.US_TEXAS,LocalDate.of(2026,11,16)));
        assertEquals(next, SessionInfoService.lookupRegularSession(LegislativeNamespace.US_TEXAS,2027));
        var leg = new Legislator(); leg.setId("LEG/us/tx/2278/test");
        leg.getTerms().add(new LegislativeTerm(LocalDate.of(2025,1,1),LocalDate.of(2027,1,1),LegiscanState.TEXAS,"1",Party.DEMOCRAT,LegislativeChamber.LOWER));
        assertFalse(leg.isMemberOfSession(next), "Prefiling must not make an outgoing-only term a member of the next session");
        leg.getTerms().add(new LegislativeTerm(LocalDate.of(2027,1,1),LocalDate.of(2029,1,1),LegiscanState.TEXAS,"2",Party.REPUBLICAN,LegislativeChamber.UPPER));
        assertTrue(leg.isMemberOfSession(next));
        assertEquals(Party.REPUBLICAN,leg.getSessionTerm().getParty());
        var old = List.of("LEG/us/tx/2160/test","Member");
        var current = List.of("LEG/us/tx/2278/test","Member");
        var departed = List.of("LEG/us/tx/2160/departed","Departed");
        assertEquals(Set.of(current,departed), new HashSet<>(LegislatorIndex.canonicalRows(List.of(old,current,departed),LocalDate.of(2026,11,16))));
    }
    @Test void futureTermsCannotChangeHistoricalPartyChamberOrLocation() {
        SessionInfoService.registerSessions(List.of(session(119),session(120)));
        var leg = new Legislator(); leg.setId("LEG/us/congress/119/test");
        leg.getTerms().add(new LegislativeTerm(LocalDate.of(2025,1,3),LocalDate.of(2027,1,3),LegiscanState.COLORADO,"1",Party.DEMOCRAT,LegislativeChamber.LOWER));
        leg.getTerms().add(new LegislativeTerm(LocalDate.of(2027,1,3),LocalDate.of(2033,1,3),LegiscanState.COLORADO,null,Party.REPUBLICAN,LegislativeChamber.UPPER));
        assertEquals(Party.DEMOCRAT,leg.getParty());
        assertEquals("CO/1",leg.getLocation());
        assertEquals(LegislativeChamber.LOWER,leg.getSessionTerm().getChamber());
        leg.setId("LEG/us/congress/120/test");
        assertEquals(Party.REPUBLICAN,leg.getParty());
        assertEquals(LegislativeChamber.UPPER,leg.getSessionTerm().getChamber());
        leg.getTerms().remove(leg.getTerms().last());
        assertFalse(leg.isMemberOfSession(session(120)), "An outgoing term ending at turnover must not overlap the new Congress");
    }
    @Test void canonicalIndexIsOrderIndependentExactAndDoesNotActivateFutureMembers() {
        SessionInfoService.registerSessions(List.of(session(119),session(120)));
        var old = List.of("LEG/us/congress/119/person","Member");
        var current = List.of("LEG/us/congress/120/person","Member");
        var departed = List.of("LEG/us/congress/119/departed","Departed");
        var freshman = List.of("LEG/us/congress/120/new","New member");
        var rows = List.of(old,current,departed,freshman);
        assertEquals(Set.of(old,departed), new HashSet<>(LegislatorIndex.canonicalRows(rows,LocalDate.of(2027,1,2))));
        assertEquals(Set.of(current,departed,freshman), new HashSet<>(LegislatorIndex.canonicalRows(rows,LocalDate.of(2027,1,3))));
        assertEquals(LegislatorIndex.canonicalRows(rows,LocalDate.of(2027,1,3)),LegislatorIndex.canonicalRows(List.of(freshman,departed,current,old),LocalDate.of(2027,1,3)));
    }
}
