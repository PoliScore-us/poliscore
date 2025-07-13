package us.poliscore.images;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.security.KeyStore;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.net.ssl.SSLContext;

import org.apache.commons.io.FilenameUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import jakarta.inject.Inject;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import us.poliscore.PoliscoreDataset;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.service.GovernmentDataService;

abstract public class AbstractLegislatorImageFetcher implements QuarkusApplication {
	
private static final String BUCKET_NAME = "poliscore-prod-public";
	
	protected S3Client client;
	
	@Inject
	protected GovernmentDataService data;
	
	protected void syncS3LegislatorImages() {
		syncS3LegislatorImages(data.getDataset());
	}
	
	@SneakyThrows
	public void syncS3LegislatorImages(PoliscoreDataset dataset)
	{
		int success = 0;
		int skipped = 0;
		
		Log.info("Building list of legislators to fetch. This will take a minute...");
		
		val legs = data.getDataset().query(Legislator.class).stream()
				.filter(l -> l.getBirthday() == null || l.getBirthday().isAfter(LocalDate.of(1900,1,1)))
				.filter(l -> l.getTerms().size() > 0 && l.getTerms().last().getStartDate().isAfter(LocalDate.of(1990,1,1)))
				.filter(l -> !exists(l))
				.toList();
		
		Log.info("About to fetch " + legs.size() + " legislator images.");
		
		for (Legislator leg : legs)	{
			try
			{
				if (syncS3LegislatorImages(leg, dataset)) {
					success++;
				} else {
					skipped++;
				}
			}
			catch (Throwable t)
			{
				Log.warn("Could not find image for congressman " + leg.getName().getOfficial_full() + " " + leg.getCode());
				t.printStackTrace();
			}
		}
		
		Log.info("Successfully imported " + success + " images. Skipped " + skipped);
	}
	
	protected boolean syncS3LegislatorImages(Legislator leg, PoliscoreDataset dataset) {
		Optional<byte[]> bytes = fetchImage(leg, dataset);
		
		if (bytes.isEmpty()) return false;
		
		upload(bytes.get(), leg.getId() + ".webp");
		
		return true;
	}
	
	protected abstract Optional<byte[]> fetchImage(Legislator leg, PoliscoreDataset dataset);
	
	@SneakyThrows
	protected CloseableHttpClient getHttpClient() {
		// Re-create HTTP client + SSL context every time (same as before)
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(CongressionalLegislatorImageFetcher.class.getResourceAsStream("keystore"), "changeit".toCharArray());

        SSLContext sslContext = SSLContexts.custom()
            .loadKeyMaterial(keyStore, null)
            .build();

        CloseableHttpClient httpClient = HttpClients.custom()
            .setSSLContext(sslContext)
            .build();
        
        return httpClient;
	}
	
	@SneakyThrows
	protected byte[] convertToWebp(byte[] input) {
	    BufferedImage image = ImageIO.read(new ByteArrayInputStream(input));
	    if (image == null) {
	        throw new IllegalArgumentException("Input bytes are not a supported image format.");
	    }

	    @Cleanup ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

	    Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
	    if (!writers.hasNext()) {
	        throw new IllegalStateException("No WebP ImageWriter found. Make sure webp-imageio is on the classpath.");
	    }

	    ImageWriter writer = writers.next();

	    @Cleanup ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream);
	    writer.setOutput(ios);

	    ImageWriteParam param = writer.getDefaultWriteParam();
	    if (param.canWriteCompressed()) {
	        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
	        param.setCompressionType("Lossy"); // or "Lossless"
	        param.setCompressionQuality(0.8f);
	    }

	    writer.write(null, new IIOImage(image, null, null), param);
	    writer.dispose();

	    return outputStream.toByteArray();
	}
	
	@SneakyThrows
	public static void enforceWebp(byte[] data) {
	    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
	        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
	        while (readers.hasNext()) {
	            ImageReader reader = readers.next();
	            String formatName = reader.getFormatName().toLowerCase();
	            if (!formatName.contains("webp")) {
	            	throw new UnsupportedOperationException("Unsupported image type [" + formatName + "]. All images must be in webp format.");
	            } else {
	            	return;
	            }
	        }
	    }
	    
	    throw new UnsupportedOperationException("Unsupported image type. All images must be in webp format.");
	}
	
	@SneakyThrows
	protected static Boolean isJPEG(byte[] bytes) throws Exception {
	    @Cleanup DataInputStream ins = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(bytes)));
	    
	    return ins.readInt() == 0xffd8ffe0;
	}
	
	protected boolean exists(Legislator leg)
	{
		try
		{
			val resp = getClient().headObject(HeadObjectRequest.builder()
					.bucket(BUCKET_NAME)
					.key(leg.getId() + ".webp")
					.build());
			
			return true;
		}
		catch (NoSuchKeyException ex)
		{
			return false;
		}
	}
	
	@SneakyThrows
	protected void upload(byte[] image, String key)
	{
		enforceWebp(image);
		
		if (!key.endsWith(".webp") && FilenameUtils.getExtension(key).isBlank())
			key = key + ".webp";
		else if (!key.endsWith(".webp") && !FilenameUtils.getExtension(key).isBlank())
			throw new UnsupportedOperationException("Unsupported image extension on key: " + key + ". All images must be in webp format.");
		
        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build();

        getClient().putObject(putOb, RequestBody.fromBytes(image));
        
        Log.info("Uploaded to S3 " + key);
	}
	
	protected S3Client getClient()
	{
		if (client == null)
		{
			client = S3Client.builder()
	                .build();
		}
		
		return client;
	}
	
	@Override
    public int run(String... args) throws Exception {
		syncS3LegislatorImages();
        
        Quarkus.waitForExit();
        return 0;
    }
	
}
