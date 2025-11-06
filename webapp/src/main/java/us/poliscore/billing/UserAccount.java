package us.poliscore.billing;

import java.time.Instant;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
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
	protected Instant updatedAt;
	
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
	
	public Instant getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public String getStorageBucket() {
		return this.getId().substring(0, StringUtils.ordinalIndexOf(getId(), "/", 1));
	}
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
}

