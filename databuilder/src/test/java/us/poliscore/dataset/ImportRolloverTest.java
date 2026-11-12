package us.poliscore.dataset;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.*;
import us.poliscore.legiscan.service.CachedLegiscanService;
import us.poliscore.legiscan.exception.LegiscanException;
import us.poliscore.legiscan.cache.CachedLegiscanDatasetResult;
import us.poliscore.legiscan.view.*;
import us.poliscore.PoliscoreUtil;
import us.poliscore.PoliscoreDataset;
import us.poliscore.dataset.augmentation.PoliscoreDatasetAugmentor;
import us.poliscore.service.GovernmentDataService;

class ImportRolloverTest {
    @Test void absentDatasetHasAnExplicitRecoverableOutcome() {
        var provider=new LegiscanDatasetProvider();
        provider.legiscan=new CachedLegiscanService("test",PoliscoreUtil.getObjectMapper(),null,1){@Override public List<LegiscanDatasetView> getDatasetList(LegiscanState state,Integer year){return List.of();}};
        assertThrows(DatasetUnavailableException.class,()->provider.importDataset(new DeploymentConfig(LegislativeNamespace.US_CONGRESS,2028,1f,true,true)));
    }
    @ParameterizedTest
    @CsvSource({"2028,true,true", "2028,false,false", "2026,true,false"})
    void emptyResultAlertIsSkippedOnlyForAnUnpublishedOptionalDataset(int year,boolean optional,boolean skippable) throws Exception {
        var failure=new LegiscanException("Alert response returned from legiscan [Empty result set]");
        var provider=new LegiscanDatasetProvider();
        provider.legiscan=new CachedLegiscanService("test",PoliscoreUtil.getObjectMapper(),null,1){
            @Override public List<LegiscanDatasetView> getDatasetList(LegiscanState state,Integer requestedYear){throw failure;}
        };
        var service=new GovernmentDataService();service.resetImports();
        var field=GovernmentDataService.class.getDeclaredField("provider");field.setAccessible(true);field.set(service,provider);
        var config=new DeploymentConfig(LegislativeNamespace.US_CONGRESS,year,1f,true,optional);
        var report=new BuildReport();
        try {
            if(skippable){
                assertTrue(service.importAvailableDataset(config,report).isEmpty());
                assertEquals(List.of("us/congress 2028"),report.getSkippedOptionalDatasets());
                assertFalse(report.hasRecovered());
                assertFalse(report.hasBlockingFatal());
            }else{
                assertSame(failure,assertThrows(DatasetUnavailableException.class,()->service.importAvailableDataset(config,report)).getCause());
                assertFalse(report.hasRecovered());
                assertTrue(report.getSkippedOptionalDatasets().isEmpty());
            }
            assertTrue(service.getAllImportedDatasets().isEmpty());
        }finally{service.resetImports();}
    }
    @ParameterizedTest
    @ValueSource(strings={"Alert response returned from legiscan [Invalid API key]",
        "Alert response returned from legiscan [API quota exceeded]", "HTTP 503: Service unavailable", "Empty result set"})
    void otherDatasetListErrorsAreNotConvertedToOptionalDatasetAbsence(String message) {
        var failure=new LegiscanException(message);
        var provider=new LegiscanDatasetProvider();
        provider.legiscan=new CachedLegiscanService("test",PoliscoreUtil.getObjectMapper(),null,1){
            @Override public List<LegiscanDatasetView> getDatasetList(LegiscanState state,Integer year){throw failure;}
        };
        assertSame(failure,assertThrows(LegiscanException.class,()->provider.importDataset(new DeploymentConfig(LegislativeNamespace.US_CONGRESS,2028,1f,true,true))));
    }
    @Test void emptyResultFromDownloadingAnAdvertisedDatasetRemainsAnError() {
        var failure=new LegiscanException("Alert response returned from legiscan [Empty result set]");
        var view=new LegiscanDatasetView();view.setStateId(6);view.setSessionId(90001);view.setYearStart(2027);view.setYearEnd(2027);
        var provider=new LegiscanDatasetProvider();
        provider.legiscan=new CachedLegiscanService("test",PoliscoreUtil.getObjectMapper(),null,1){
            @Override public List<LegiscanDatasetView> getDatasetList(LegiscanState state,Integer year){return List.of(view);}
            @Override public CachedLegiscanDatasetResult cacheDataset(LegiscanDatasetView input){throw failure;}
        };
        assertSame(failure,assertThrows(LegiscanException.class,()->provider.importDataset(new DeploymentConfig(LegislativeNamespace.US_COLORADO,2027,1f,true,true))));
    }
    @Test void congress120UsesLogical2028DatasetWithoutAFiniteEnumFailure() {
        var session=LegiscanDatasetProvider.buildSession(true,1,LegiscanState.CONGRESS,2027,2028);
        assertEquals("120",session.getCode());
        assertEquals(2028,session.getEndDate().getYear());
    }
    @Test void malformedMembersMakeTheBuildFailRatherThanPublishingAPartialRoster() {
        var view=new LegiscanDatasetView();view.setStateId(6);view.setSessionId(90001);view.setYearStart(2027);view.setYearEnd(2027);
        var cached=new CachedLegiscanDatasetResult(null,view,PoliscoreUtil.getObjectMapper());
        var malformed=new LegiscanPeopleView();malformed.setName("Malformed member");malformed.setPeopleId(1);
        cached.getPeople().put(1,malformed);
        var provider=new LegiscanDatasetProvider();
        provider.legiscan=new CachedLegiscanService("test",PoliscoreUtil.getObjectMapper(),null,1){
            @Override public List<LegiscanDatasetView> getDatasetList(LegiscanState state,Integer year){return List.of(view);}
            @Override public CachedLegiscanDatasetResult cacheDataset(LegiscanDatasetView input){return cached;}
        };
        provider.augmentor=new PoliscoreDatasetAugmentor(){@Override public void augmentLegislators(PoliscoreDataset input){}};
        var report=new BuildReport();
        assertThrows(IllegalStateException.class,()->provider.importDataset(new DeploymentConfig(LegislativeNamespace.US_COLORADO,2027,1f,true,true),report));
        assertTrue(report.hasBlockingFatal());
        assertTrue(report.getFatalErrors().getFirst().getMessage().contains("Incomplete session import"));
    }
}
