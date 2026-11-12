package us.poliscore.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.dataset.*;
import us.poliscore.model.*;

class DatasetRolloverTest {
    abstract static class ReadOnlyProvider implements DatasetProvider {
        @Override public void syncS3LegislatorImages(PoliscoreDatasetIF d) { fail("No image writes in this test"); }
        @Override public void syncS3BillText(PoliscoreDatasetIF d) { fail("No text writes in this test"); }
    }
    void inject(Object instance,String name,Object value) throws Exception {
        var f=GovernmentDataService.class.getDeclaredField(name);f.setAccessible(true);f.set(instance,value);
    }
    @Test void missingOptionalUpstreamIsReportedButProviderFailuresAreNotHidden() throws Exception {
        var service=new GovernmentDataService();service.resetImports();
        var planned=new DeploymentConfig(LegislativeNamespace.US_CONGRESS,2028,1.7f,true,true);
        var report=new BuildReport();
        inject(service,"provider",new ReadOnlyProvider(){
            @Override public PoliscoreDatasetIF importDataset(DeploymentConfig config){throw new DatasetUnavailableException("Not yet published");}
        });
        assertTrue(service.importAvailableDataset(planned,report).isEmpty());
        assertFalse(report.hasRecovered());assertFalse(report.hasFatal());
        assertEquals(List.of("us/congress 2028"),report.getSkippedOptionalDatasets());
        assertTrue(report.toString().contains("Skipped optional datasets (not yet available):\n- us/congress 2028"));
        assertFalse(report.toString().contains("ERROR"));
        assertFalse(report.toString().contains("DatasetUnavailableException"));
        service.importAvailableDataset(planned,report);
        assertEquals(1,report.getSkippedOptionalDatasets().size(),"Repeated checks should not duplicate the report entry");
        var published=new DeploymentConfig(LegislativeNamespace.US_CONGRESS,2026,1f,true,true);
        assertThrows(DatasetUnavailableException.class,()->service.importAvailableDataset(published,report),
                "An already published dataset must not disappear as a pending optional entry");
        planned.setOptional(false);
        assertThrows(DatasetUnavailableException.class,()->service.importAvailableDataset(planned,report));
        planned.setOptional(true);
        var failure=new IllegalStateException("Invalid roster");
        inject(service,"provider",new ReadOnlyProvider(){
            @Override public PoliscoreDatasetIF importDataset(DeploymentConfig config){throw failure;}
        });
        assertSame(failure,assertThrows(IllegalStateException.class,()->service.importAvailableDataset(planned,report)));
        service.resetImports();
    }
    @Test void stagedConfigurationIsNotAdvertisedWithoutRealSessionMetadata() {
        var config=new PoliscoreConfigService();config.init();
        var all=config.getConfiguredDeployments();
        assertTrue(all.stream().anyMatch(d->d.getNamespace().equals(LegislativeNamespace.US_CONGRESS)&&d.getYear()==2028&&d.isOptional()));
        assertTrue(config.getSupportedDeployments().stream().allMatch(d->!d.isOptional()||SessionInfoService.getSessions().stream().anyMatch(s->s.isRegular()&&s.getNamespace().equals(d.getNamespace())&&s.isYearWithin(d.getYear()))));
    }
}
