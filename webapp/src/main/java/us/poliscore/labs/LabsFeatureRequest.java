package us.poliscore.labs;

import java.time.Instant;
import java.time.LocalDate;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import us.poliscore.model.Persistable;

@DynamoDbBean
@RegisterForReflection
@Data
@NoArgsConstructor
public class LabsFeatureRequest implements Persistable {
	
	public static final String ID_CLASS_PREFIX = "LAB";
	
	public static String generateId(String featureId, String customerId) {
		return ID_CLASS_PREFIX + "/" + featureId + "/" + customerId;
	}
	
	@NonNull
	protected String id;
	
	protected String featureId;
	protected String customerId;
	protected String customerEmail;
	protected Instant lastUpdate = Instant.now();
	
	@DynamoDbPartitionKey
	@Override public String getId() { return id; }
	@Override public void setId(String id) {
		if (!id.startsWith(ID_CLASS_PREFIX + "/"))
			this.id = ID_CLASS_PREFIX + "/" + id;
		else
			this.id = id;
	}
	
	// These date fields are required for DDB otherwise the object won't have a 'date' field and won't participate in our index
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public Instant getDate() { return lastUpdate; }
	@JsonIgnore public void setDate(Instant updatedAt) { this.lastUpdate = updatedAt; }
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public String getStorageBucket() {
		return this.getId().substring(0, StringUtils.ordinalIndexOf(getId(), "/", 1));
	}
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
}

