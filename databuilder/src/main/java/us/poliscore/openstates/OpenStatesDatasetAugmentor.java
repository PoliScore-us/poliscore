package us.poliscore.openstates;

import java.io.InputStreamReader;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreDataset;
import us.poliscore.images.AbstractLegislatorImageFetcher;
import us.poliscore.model.legislator.Legislator;

/**
 * Fetches and integrates bulk legislator data from OpenStates
 * https://open.pluralpolicy.com/data/
 * 
 * Provides two separate services:
 * 1. augmentDataset - Fetches data from OpenStates and import all birthdates into the Legislators for the dataset (useful since legiscan doesn't provide birthdates
 * 2. syncS3LegislatorImages - Fetches data from OpenStates and uploads to S3 all the referenced legislator image URLs.
 */
@QuarkusMain(name="OpenStatesDatasetAugmentor")
public class OpenStatesDatasetAugmentor extends AbstractLegislatorImageFetcher implements QuarkusApplication {

	protected final FileSystemOpenStatesCache cache = new FileSystemOpenStatesCache(
            new java.io.File(System.getProperty("user.home") + "/appdata/poliscore/openstates"),
            new ObjectMapper(),
            86400 // default TTL 24h
    );
	
	protected void augmentDataset(PoliscoreDataset dataset) {
		int augmentCount = 0;
		
		for(var leg : dataset.query(Legislator.class)) {
			val legData = fetchLegislatorData(leg, dataset).orElse(null);
			
			if (legData != null && legData.getBirthDate() != null) {
				leg.setBirthday(legData.getBirthDate());
			}
		}
		
		Log.info("OpenStatesDatasetAugmentor augmented birthdates for " + augmentCount + " legislators.");
	}

    @SneakyThrows
    public List<OpenStatesLegislatorData> fetchOpenStatesData(PoliscoreDataset dataset) {
        String abbr = dataset.getSession().getNamespace().toAbbreviation().toLowerCase();
        String cacheKey = "people/current/" + abbr;

        Optional<List<OpenStatesLegislatorData>> cached = cache.getOrExpire(cacheKey);
        if (cached.isPresent()) return cached.get();

        String csvUrl = "https://data.openstates.org/people/current/" + abbr + ".csv";

        RetryPolicy<Object> retryPolicy = RetryPolicy.builder()
                .handle(Exception.class)
                .withBackoff(Duration.ofSeconds(1), Duration.ofSeconds(5))
                .withMaxAttempts(3)
                .onRetry(e -> Log.warn("Retrying fetch from OpenStates: " + e.getLastException().getMessage()))
                .build();

        List<OpenStatesLegislatorData> data = Failsafe.with(retryPolicy).get(() -> {
            @Cleanup InputStreamReader reader = new InputStreamReader(new URL(csvUrl).openStream());
            CsvToBean<OpenStatesLegislatorData> csvToBean = new CsvToBeanBuilder<OpenStatesLegislatorData>(reader)
                    .withType(OpenStatesLegislatorData.class)
                    .withIgnoreLeadingWhiteSpace(true)
                    .build();
            return csvToBean.parse();
        });

        cache.put(cacheKey, data);
        return data;
    }
    
    public Optional<OpenStatesLegislatorData> fetchLegislatorData(Legislator leg, PoliscoreDataset dataset) {
    	List<OpenStatesLegislatorData> allData = fetchOpenStatesData(dataset);

        Optional<OpenStatesLegislatorData> match = allData.stream()
                .filter(l -> l.getFamilyName() != null && l.getGivenName() != null)
                .filter(l -> l.getFamilyName().trim().equalsIgnoreCase(leg.getName().getLast())
                          && l.getGivenName().trim().equalsIgnoreCase(leg.getName().getFirst()))
                .findFirst();

        return match;
    }

    @SneakyThrows
    @Override
    protected Optional<byte[]> fetchImage(Legislator leg, PoliscoreDataset dataset) {
        val legData = fetchLegislatorData(leg, dataset);
        
        if (legData.isEmpty() || legData.get().getImage() == null || legData.get().getImage().trim().isEmpty()) {
            return Optional.empty();
        }

        val client = getHttpClient();
        val imgResp = client.execute(new org.apache.http.client.methods.HttpGet(legData.get().getImage()));
        if (imgResp.getStatusLine().getStatusCode() != 200) return Optional.empty();

        byte[] rawBytes = imgResp.getEntity().getContent().readAllBytes();
        byte[] webpBytes = convertToWebp(rawBytes);

        return Optional.of(webpBytes);
    }

    public static void main(String[] args) {
        Quarkus.run(OpenStatesDatasetAugmentor.class, args);
    }
}  