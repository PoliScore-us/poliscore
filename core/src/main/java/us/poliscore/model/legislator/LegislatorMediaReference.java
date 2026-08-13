package us.poliscore.model.legislator;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.AIInterpretationMetadataConverter;

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@DynamoDbBean
@RegisterForReflection
@NoArgsConstructor
public class LegislatorMediaReference extends SessionPersistable {
	public static final String ID_CLASS_PREFIX = "LMR";

	public static String generateId(String legislatorId, InterpretationOrigin origin) {
		if (origin == null || origin.getUrl() == null || origin.getUrl().isBlank()) {
			throw new IllegalArgumentException("A media reference URL is required");
		}
		if (legislatorId == null || !legislatorId.startsWith(Legislator.ID_CLASS_PREFIX + "/")) {
			throw new IllegalArgumentException("Not a Legislator id: " + legislatorId);
		}

		return legislatorId.replaceFirst("^" + Legislator.ID_CLASS_PREFIX, ID_CLASS_PREFIX)
				+ "-" + urlHash(origin.getUrl());
	}

	static String canonicalizeUrl(String url) {
		try {
			URI parsed = URI.create(url.trim()).normalize();
			String scheme = parsed.getScheme() == null ? null : parsed.getScheme().toLowerCase();
			String host = parsed.getHost() == null ? null : parsed.getHost().toLowerCase();
			int port = parsed.getPort();
			if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
				port = -1;
			}
			String path = parsed.getPath();
			if (path != null && path.length() > 1 && path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
			return new URI(scheme, parsed.getUserInfo(), host, port, path, parsed.getQuery(), null).toASCIIString();
		} catch (Exception ignored) {
			return url.trim();
		}
	}

	private static String urlHash(String url) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonicalizeUrl(url).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (Exception e) {
			throw new IllegalStateException("Unable to hash media reference URL", e);
		}
	}

	protected String genArticleTitle = "";
	protected String shortExplain = "";
	protected String longExplain = "";
	protected String author = "";
	protected String type = "";
	protected String publishedDate = "";
	protected int confidence = -1;
	protected int sentiment = Integer.MIN_VALUE;
	protected String sentimentText;
	protected int trustworthiness = -1;
	protected boolean noInterp = true;

	@NonNull
	protected String legislatorId;

	@NonNull
	protected InterpretationOrigin origin;

	@NonNull
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(AIInterpretationMetadataConverter.class)}))
	protected AIInterpretationMetadata metadata;

	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX }) public String getStorageBucket() { return super.getStorageBucket(); }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }

	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return metadata.getDate(); }
	@JsonIgnore public void setDate(LocalDate date) { }

	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return sentiment; }
	@JsonIgnore public void setRating(int rating) { }
}
