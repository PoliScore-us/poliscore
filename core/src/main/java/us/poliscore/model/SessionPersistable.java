package us.poliscore.model;

import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

/**
 * An object which is specific to a legislative session.
 * 
 * The id follows a predictable pattern of:
 * ID_CLASS_PREFIX/name/space/sessionCode/code 
 */
@Data
@DynamoDbBean
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@RegisterForReflection
@NoArgsConstructor
abstract public class SessionPersistable implements Persistable {
	
	@NonNull
	protected String id;
	
	@DynamoDbPartitionKey
	@EqualsAndHashCode.Include
	public String getId()
	{
		return id;
	}
	
	public void setId(String id) { this.id = id; }
	
	@JsonIgnore
	@DynamoDbIgnore
	public String getCode() {
		return Arrays.asList(this.id.split("/")).getLast();
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public String getSessionCode() {
		return this.id.split("/")[3];
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public LegislativeNamespace getNamespace() {
		return LegislativeNamespace.of(this.id.split("/")[1] + "/" + this.id.split("/")[2]);
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX, Persistable.OBJECT_BY_RATING_ABS_INDEX, Persistable.OBJECT_BY_LOCATION_INDEX, Persistable.OBJECT_BY_IMPACT_INDEX, Persistable.OBJECT_BY_IMPACT_ABS_INDEX}) public String getStorageBucket() {
		return this.getId().substring(0, StringUtils.ordinalIndexOf(getId(), "/", 4));
	}
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	public static String generateId(String idClassPrefix, LegislativeNamespace ns, String sessionCode, String objectCode)
	{
		return idClassPrefix + "/" + ns.getNamespace() + "/" + sessionCode + "/" + objectCode;
	}
	
}
