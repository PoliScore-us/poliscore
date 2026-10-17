package us.poliscore.model;

import java.time.LocalDate;
import java.util.List;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import us.poliscore.model.LegislativeNamespace.NamespaceConverter;
import us.poliscore.model.LegislativeNamespace.NamespaceDeserializer;
import us.poliscore.model.LegislativeNamespace.NamespaceSerializer;

@Data
@EqualsAndHashCode(of = {"code", "namespace"})
@NoArgsConstructor
@DynamoDbBean
public class LegislativeSession {
	
	protected boolean regular = true;
	
	protected LocalDate startDate;
	
	protected LocalDate endDate;
	
	protected String code;

    @Getter(onMethod = @__({ @DynamoDbIgnore }))
    protected List<SessionCalendar> calendar;

    // Retain the dataset constructor; calendar metadata is optional.
    public LegislativeSession(boolean regular, LocalDate startDate, LocalDate endDate,
            String code, LegislativeNamespace namespace) {
        this.regular = regular;
        this.startDate = startDate;
        this.endDate = endDate;
        this.code = code;
        this.namespace = namespace;
    }

    public record SessionCalendar(String label, String startDate, String endDate,
            String sourceUrl, String linkUrl, String linkType) {}

	
	@JsonSerialize(using = NamespaceSerializer.class)
	@JsonDeserialize(using = NamespaceDeserializer.class)
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(NamespaceConverter.class) }))
	protected LegislativeNamespace namespace;
	
	@JsonIgnore
	public boolean isOver() {
		return LocalDate.now().isAfter(endDate);
	}
	
	@JsonIgnore
	public String getKey() {
		return namespace.getNamespace() + "/" + code;
	}

	public boolean isYearWithin(int year) {
		return startDate.getYear() <= year && endDate.getYear() >= year; 
	}
	
	@JsonProperty(access = Access.READ_ONLY)
	public String getDescription() {
		return namespace.getDescription() + " " + startDate.getYear() + "-" + endDate.getYear() + " (" + code + ")";
	}
	
}
