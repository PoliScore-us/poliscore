package us.poliscore.model.bill;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;

@Data
@DynamoDbBean
@RegisterForReflection
@AllArgsConstructor
@NoArgsConstructor
public class BillSlice {
	@JsonIgnore
	@Getter(onMethod = @__({ @DynamoDbIgnore }))
	private transient Bill bill;
	
	@JsonIgnore
	@Getter(onMethod = @__({ @DynamoDbIgnore }))
	private transient String text;
	
	private int sliceIndex;
	
	private String name;
	
	private String description;
	
	private String start;
	
	private String end;
}
