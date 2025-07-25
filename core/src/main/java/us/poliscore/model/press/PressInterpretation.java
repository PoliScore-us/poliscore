package us.poliscore.model.press;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.AIInterpretationMetadataConverter;
import us.poliscore.model.legislator.LegislatorInterpretation;

@Data
@EqualsAndHashCode(callSuper = true)
@DynamoDbBean
@RegisterForReflection
@NoArgsConstructor
@RequiredArgsConstructor
public class PressInterpretation extends SessionPersistable {
	public static final String ID_CLASS_PREFIX = "PIT";
	
	public static String generateId(String billId)
	{
		return generateId(billId, InterpretationOrigin.POLISCORE);
	}
	
	public static String generateId(String billId, InterpretationOrigin origin)
	{
		var id = billId.replace(Bill.ID_CLASS_PREFIX, ID_CLASS_PREFIX);
		
		if (origin != null)
			id += "-" + origin.getIdHash();
		
		return id;
	}
	
//	@JsonIgnore
//	@Getter(onMethod_ = {@DynamoDbIgnore})
//	protected transient Bill bill;
	
	protected String genArticleTitle = "";
	
	protected String shortExplain = "";
	
	protected String longExplain = "";
	
	protected String author = "";
	
	protected int confidence = -1;
	
	protected int sentiment = Integer.MIN_VALUE;
	
	protected boolean noInterp = true;
	
	@NonNull
	protected String billId;
	
	@NonNull
	protected InterpretationOrigin origin;
	
	@NonNull
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(AIInterpretationMetadataConverter.class)}))
	protected AIInterpretationMetadata metadata;
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX }) public String getStorageBucket() { return super.getStorageBucket(); }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return metadata.getDate(); }
	@JsonIgnore public void setDate(LocalDate date) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return sentiment; }
	@JsonIgnore public void setRating(int rating) { }
}
