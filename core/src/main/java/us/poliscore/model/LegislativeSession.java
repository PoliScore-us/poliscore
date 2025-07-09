package us.poliscore.model;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@Data
@AllArgsConstructor
@EqualsAndHashCode(of = {"key", "namespace"})
@NoArgsConstructor
@DynamoDbBean
public class LegislativeSession {
	
	protected LocalDate startDate;
	
	protected LocalDate endDate;
	
	protected String key;
	
	protected LegislativeNamespace namespace;
	
	@JsonIgnore
	public boolean isOver() {
		return LocalDate.now().isAfter(endDate);
	}
	
}
