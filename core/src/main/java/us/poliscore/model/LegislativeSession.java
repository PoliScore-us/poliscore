package us.poliscore.model;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import us.poliscore.model.LegislativeNamespace.*;

@Data
@AllArgsConstructor
@EqualsAndHashCode(of = {"code", "namespace"})
@NoArgsConstructor
@DynamoDbBean
public class LegislativeSession {
	
	protected LocalDate startDate;
	
	protected LocalDate endDate;
	
	protected String code;
	
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
	
	public String getDescription() {
		return namespace.getDescription() + " " + startDate.getYear() + "-" + endDate.getYear() + " (" + code + ")";
	}
	
}
