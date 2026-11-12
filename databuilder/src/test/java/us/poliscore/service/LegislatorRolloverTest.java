package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import us.poliscore.*;
import us.poliscore.dataset.*;
import us.poliscore.model.*;
import us.poliscore.model.bill.*;
import us.poliscore.model.legislator.*;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.service.storage.LocalCachedS3Service;

class LegislatorRolloverTest {
    PoliscoreDataset dataset(String session, int start) {
        return new PoliscoreDataset(new LegislativeSession(true,LocalDate.of(start,1,1),LocalDate.of(start+1,12,31),session,LegislativeNamespace.US_CONGRESS),new PoliscoreDataset.DeploymentConfig(LegislativeNamespace.US_CONGRESS,start+1,1f,true));
    }
    Legislator leg(String session,String person) {
        var l = new Legislator(); l.setId("LEG/us/congress/"+session+"/"+person); l.setName(new Legislator.LegislatorName("Test","Member","Test Member"));return l;
    }
    LegislatorBillSponsor interaction(Legislator leg,int number,int score) {
        var i = new LegislatorBillSponsor(); i.setLegId(leg.getId());i.setBillId("BIL/us/congress/"+leg.getSessionCode()+"/hr/"+number);
        i.setBillName("Bill "+number);i.setDate(LocalDate.of(2026,1,1));i.setRating(score);
        var stats=new IssueStats();stats.setStat(TrackedIssue.OverallBenefitToSociety,score);i.setIssueStats(stats);return i;
    }
    void inject(Object service,String name,Object value) throws Exception {
        var f=LegislatorInterpretationService.class.getDeclaredField(name);f.setAccessible(true);f.set(service,value);
    }
    @Test void carryoverIsIdempotentAndDoesNotMutatePreviousRecordsOrGiveNewcomersAPredecessorsScore() throws Exception {
        var prev=dataset("119",2025); var next=dataset("120",2027);
        var old=leg("119","same"); var returning=leg("120","same");var freshman=leg("120","new");
        for(int n=1;n<=30;n++) old.addBillInteraction(interaction(old,n,40));
        prev.put(old);next.put(returning);next.put(freshman);
        String before=PoliscoreUtil.getObjectMapper().writeValueAsString(old);
        var narrative=new LegislatorInterpretation(LegislativeNamespace.US_CONGRESS,"119","same",AIInterpretationMetadata.construct("test","old-model",1,false),null);
        narrative.setLongExplain("Prior report");narrative.setShortExplain("Prior focus");narrative.setConstituency("Prior constituency");narrative.setCampaignFinance("Prior finance");
        var service=new LegislatorInterpretationService() {
            @Override public void updateInteractionsInterp(Legislator leg) {}
            @Override public void calculateImpactAndRating(Legislator leg,LegislatorInterpretation interp) {}
        };
        inject(service,"data",new GovernmentDataService(){@Override public PoliscoreDatasetIF getPreviousDataset(PoliscoreDatasetIF d){return prev;}});
        inject(service,"s3",new LocalCachedS3Service(){@Override public <T extends Persistable> Optional<T> get(String id,Class<T> type){return id.equals(narrative.getId())?Optional.of(type.cast(narrative)):Optional.empty();}});
        inject(service,"openAiService",new OpenAIService(){@Override public AIInterpretationMetadata metadata(){return AIInterpretationMetadata.construct("test","new-model",1,false);}});
        inject(service,"runtimeConfig",new us.poliscore.entrypoint.DatabaseBuilderConfig());
        service.recalculateLegislators(next);
        assertEquals(30,returning.getInteractions().size());
        var reportJson=PoliscoreUtil.getObjectMapper().valueToTree(returning.getInterpretation());
        assertFalse(reportJson.has("reportSourceSession"));
        assertFalse(reportJson.has("carriedInteractionCount"));
        assertFalse(reportJson.has("currentSessionInteractionCount"));
        assertEquals("Prior finance",returning.getInterpretation().getCampaignFinance());
        assertEquals("Prior constituency",returning.getInterpretation().getConstituency());
        assertEquals("old-model",returning.getInterpretation().getMetadata().getModel());
        assertEquals(40,returning.getInterpretation().getIssueStats().getStat(TrackedIssue.OverallBenefitToSociety));
        assertEquals(40,returning.getInteractions().getFirst().getRating());
        assertEquals(old.getId(),returning.getInteractions().getFirst().getLegId());
        assertFalse(PoliscoreUtil.getObjectMapper().writeValueAsString(returning).contains("historyWeight"));
        assertNotSame(old.getInteractions().getFirst(),returning.getInteractions().getFirst());
        assertNull(freshman.getInterpretation().getIssueStats());
        service.recalculateLegislators(next);
        assertEquals(30,returning.getInteractions().size());
        assertEquals(before,PoliscoreUtil.getObjectMapper().writeValueAsString(old));
    }
    @Test void intendedAndRealizedScoresRecognizeHistoricalEvidenceAfterReload() throws Exception {
        var service=new LegislatorInterpretationService(){@Override protected boolean isEnactedBill(String id){return true;}};
        var leg=leg("120","same");var history=interaction(leg("119","same"),1,100);
        leg.setInteractions(List.of(history,interaction(leg,2,0)));
        var mapper=PoliscoreUtil.getObjectMapper();
        leg=mapper.readValue(mapper.writeValueAsString(leg),Legislator.class);
        assertEquals(47,service.calculateAgregateInteractionStats(leg).toIssueStats().getStat(TrackedIssue.OverallBenefitToSociety));
        var interp=new LegislatorInterpretation();service.calculateRealizedStats(leg,interp);
        assertEquals(47,interp.getRealizedIssueStats().getStat(TrackedIssue.OverallBenefitToSociety));
        service.backfillInteractionsFromPreviousSession(leg,dataset("119",2025));
        assertEquals(1,leg.getInteractions().size());
        assertEquals(0,service.calculateAgregateInteractionStats(leg).toIssueStats().getStat(TrackedIssue.OverallBenefitToSociety));
    }
    @Test void impactDiscountIsAppliedOnlyWhenScoringHistoricalEvidence() throws Exception {
        var service=new LegislatorInterpretationService();
        inject(service,"billService",new BillService(){@Override public Optional<BillInterpretation> getInterpretation(String id){return Optional.empty();}});
        var leg=leg("120","same");
        var history=interaction(leg("119","same"),1,100);
        var current=interaction(leg,1,100);
        history.setStatusProgress(.5f);current.setStatusProgress(.5f);
        leg.setInteractions(List.of(history,current));
        int originalImpact=history.getImpact();
        service.calculateImpactAndRating(leg,new LegislatorInterpretation());
        assertEquals((long) Math.round(originalImpact*.9f)+current.getImpact(),leg.getImpact());
        assertEquals(originalImpact,history.getImpact());
        var archived=leg("119","same");archived.setInteractions(List.of(history));
        service.calculateImpactAndRating(archived,new LegislatorInterpretation());
        assertEquals((long) originalImpact,archived.getImpact());
    }
    @Test void carryoverCanRefreshAnUnscoredCosponsorWithoutMutatingThePreviousSession() {
        var namespace=LegislativeNamespace.US_COLORADO;
        var prev=new PoliscoreDataset(new LegislativeSession(true,LocalDate.of(2025,1,1),LocalDate.of(2025,12,31),"2173",namespace),new PoliscoreDataset.DeploymentConfig(namespace,2025,1f,true));
        var old=leg("2173","same");old.setId("LEG/us/co/2173/same");
        var returning=leg("2243","same");returning.setId("LEG/us/co/2243/same");
        var unscored=new LegislatorBillInteraction.LegislatorBillCosponsor();
        unscored.setLegId(old.getId());unscored.setBillId("BIL/us/co/2173/hb/1");
        unscored.setBillName("Previously unscored bill");unscored.setDate(LocalDate.of(2025,2,1));
        old.addBillInteraction(unscored);prev.put(old);
        var service=new LegislatorInterpretationService(){
            @Override public void updateInteractionsInterp(Legislator copy) {
                var interaction=copy.getInteractions().getFirst();
                assertNotSame(unscored,interaction);
                var stats=new IssueStats();stats.setStat(TrackedIssue.OverallBenefitToSociety,40);
                interaction.setIssueStats(stats);
            }
        };
        assertDoesNotThrow(()->service.backfillInteractionsFromPreviousSession(returning,prev));
        assertEquals(1,returning.getInteractions().size());
        assertEquals(28,returning.getInteractions().getFirst().getRating());
        assertNull(unscored.getIssueStats());
        assertNull(unscored.getRating());
    }
}
