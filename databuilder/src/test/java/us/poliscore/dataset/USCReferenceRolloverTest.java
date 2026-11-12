package us.poliscore.dataset;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import us.poliscore.PoliscoreUtil;

class USCReferenceRolloverTest {
    @Test void refreshesBothReferenceFilesInMemoryAndExpiresTheCache() throws Exception {
        int[] calls = {0};
        var provider = new USCDatasetProvider() {
            @Override protected JsonNode fetchLegislatorReference(String filename) throws java.io.IOException {
                calls[0]++;
                return PoliscoreUtil.getObjectMapper().readTree("[{\"id\":{\"bioguide\":\"NEW\"},\"name\":{\"first\":\"New\",\"last\":\"Member\"},\"terms\":[]}]");
            }
        };
        assertEquals(2,provider.referenceLegislators().size());
        assertEquals("NEW",provider.referenceLegislators().getFirst().getId().getBioguide());
        assertEquals(2,calls[0],"Both files should be cached across datasets in the same run");
        var expiry=USCDatasetProvider.class.getDeclaredField("referencesExpireAt");expiry.setAccessible(true);expiry.set(provider,java.time.Instant.MIN);
        provider.referenceLegislators();
        assertEquals(4,calls[0],"A long-lived scheduled builder must refresh after expiry");
    }
}
