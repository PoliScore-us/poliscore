package us.poliscore.billing;

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
public class UserAccount implements Persistable {
	
	public static final String ID_CLASS_PREFIX = "UAC";
	
	@NonNull
	protected String id;
	
	protected String subscriptionId;
	protected String priceId;
	
	protected String email;
	protected String stripeCustomerId;
	protected String plan;
	protected String status;
	protected Instant lastUpdate = Instant.now();
	
	protected Long currentPeriodEnd;     // epoch seconds
	protected Boolean cancelAtPeriodEnd; // true if set to cancel at term end
	
	@DynamoDbPartitionKey
	@Override public String getId() { return id; }
	@Override public void setId(String id) {
		if (!id.startsWith(ID_CLASS_PREFIX + "/"))
			this.id = ID_CLASS_PREFIX + "/" + id;
		else
			this.id = id;
	}
	
	public String getEmail() { return email; }
	public void setEmail(String email) { this.email = email; }
	
	public String getStripeCustomerId() { return stripeCustomerId; }
	public void setStripeCustomerId(String id) { this.stripeCustomerId = id; }
	
	public String getPlan() { return plan; }
	public void setPlan(String plan) { this.plan = plan; }
	
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	
	// We're overloading an index here so we can reuse an existing index. This is to allow lookup by stripe id.
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_LOCATION_INDEX }) public String getLocation() { return stripeCustomerId; }
	@JsonIgnore public void setLocation(String location) { this.stripeCustomerId = location; }
	
	// These date fields are required for DDB otherwise the object won't have a 'date' field and won't participate in our index
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public Instant getDate() { return lastUpdate; }
	@JsonIgnore public void setDate(Instant updatedAt) { this.lastUpdate = updatedAt; }
	
	public Instant getLastUpdate() { return lastUpdate; }
	public void setLastUpdate(Instant updatedAt) { this.lastUpdate = updatedAt; }
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_LOCATION_INDEX }) public String getStorageBucket() {
		return this.getId().substring(0, StringUtils.ordinalIndexOf(getId(), "/", 1));
	}
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
}

