package us.poliscore.model;

import java.time.LocalDateTime;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
@MappedSuperclass
@Access(AccessType.FIELD)
abstract public class SessionPersistable implements Persistable {
	
	@NonNull
	@Id
	@Column(name = "id", nullable = false)
	protected String id;
	
	@Convert(converter = LocalDateTimeStringConverter.class)
	@Column(name = "last_update_value")
	protected LocalDateTime lastUpdate;
	
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
		int sessionSlash = StringUtils.ordinalIndexOf(this.id, "/", 4);
		
		if (sessionSlash == -1) return null;
		
		return this.id.substring(sessionSlash + 1);
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
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX, Persistable.OBJECT_BY_RATING_ABS_INDEX, Persistable.OBJECT_BY_LOCATION_INDEX, Persistable.OBJECT_BY_IMPACT_INDEX, Persistable.OBJECT_BY_IMPACT_ABS_INDEX})
	public String getStorageBucket() {
		int sessionSlash = StringUtils.ordinalIndexOf(getId(), "/", 4);
		
		if (sessionSlash == -1) return id;
		
		return this.getId().substring(0, sessionSlash);
	}
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	public static String generateId(String idClassPrefix, LegislativeNamespace ns, String sessionCode, String objectCode)
	{
		return idClassPrefix + "/" + ns.getNamespace() + "/" + sessionCode + "/" + objectCode;
	}

	@PrePersist
	@PreUpdate
	protected void syncJpaState()
	{
		synchronizeJpaState();
	}

	protected void synchronizeJpaState() { }

	@Converter
	public static class LocalDateTimeStringConverter implements AttributeConverter<LocalDateTime, String> {

		@Override
		public String convertToDatabaseColumn(LocalDateTime attribute) {
			return attribute == null ? null : attribute.toString();
		}

		@Override
		public LocalDateTime convertToEntityAttribute(String dbData) {
			return dbData == null || dbData.isBlank() ? null : LocalDateTime.parse(dbData);
		}
	}
	
}
