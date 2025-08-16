package us.poliscore.model;

import java.time.LocalDate;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@Data
@DynamoDbBean
@RegisterForReflection
@NoArgsConstructor
@AllArgsConstructor
public class Subscriber implements Persistable {
	
	public static final String ID_CLASS_PREFIX = "SUB";
	
	protected String email;
	protected String category;
	protected LocalDate createDate;
	
	@Override
	public String getId() {
		return ID_CLASS_PREFIX + "/" + email;
	}
	@Override
	public void setId(String id) { }
	
	@Override
	public String getStorageBucket() { return ID_CLASS_PREFIX; }
	
	@Override
	public void setStorageBucket(String prefix) { }
	
}
