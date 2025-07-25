package us.poliscore.model.bill;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.AISliceInterpretationMetadata;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.IssueStats;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.AIInterpretationMetadataConverter;
import us.poliscore.model.legislator.LegislatorInterpretation;
import us.poliscore.model.press.PressInterpretation;

@Data
@EqualsAndHashCode(callSuper = true)
@DynamoDbBean
@RegisterForReflection
@NoArgsConstructor
@AllArgsConstructor
public class BillInterpretation extends SessionPersistable
{
	public static final String ID_CLASS_PREFIX = "BIT";
	
	public static String generateId(String billId, Integer sliceIndex)
	{
		return generateId(billId, InterpretationOrigin.POLISCORE, sliceIndex);
	}
	
	public static String generateId(String billId, InterpretationOrigin origin, Integer sliceIndex)
	{
		var id = billId.replace(Bill.ID_CLASS_PREFIX, ID_CLASS_PREFIX);
		
		if (origin != null)
			id += "-" + origin.getIdHash();
		
		if (sliceIndex != null)
		{
			id += "-" + sliceIndex;
		}
		
		return id;
	}
	
	@JsonIgnore
	@Getter(onMethod_ = {@DynamoDbIgnore})
	protected transient Bill bill;
	
	protected IssueStats issueStats;
	
	protected String genBillTitle;
	
	protected List<String> riders;
	
	protected String author;
	
	protected String shortExplain;
	
	protected String longExplain;
	
	protected Integer confidence;
	
	@NonNull
	protected String billId;
	
	protected InterpretationOrigin origin = InterpretationOrigin.POLISCORE;
	
	protected List<PressInterpretation> pressInterps = new ArrayList<PressInterpretation>();
	
	protected LocalDate lastPressQuery = LocalDate.EPOCH;
	
	@NonNull
	protected List<BillInterpretation> sliceInterpretations = new ArrayList<BillInterpretation>();
	
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(AIInterpretationMetadataConverter.class)}))
	protected AIInterpretationMetadata metadata;
	
	public List<PressInterpretation> getPressInterps() {
		if (pressInterps == null) return new ArrayList<PressInterpretation>();
		
		return pressInterps;
	}
	
	public void setBill(Bill bill)
	{
		this.bill = bill;
		billId = bill.getId();
	}
	
	@JsonIgnore
	public String getName()
	{
		if (metadata instanceof AISliceInterpretationMetadata)
		{
			return bill.getName() + "-" + ((AISliceInterpretationMetadata)metadata).getSliceIndex();
		}
		else
		{
			return bill.getName();
		}
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX }) public String getStorageBucket() { return super.getStorageBucket(); }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return metadata.getDate(); }
	@JsonIgnore public void setDate(LocalDate date) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return issueStats.getRating(); }
	@JsonIgnore public Integer getRating(TrackedIssue issue) { return issueStats.getRating(issue); }
	@JsonIgnore public void setRating(int rating) { }

	public boolean hasPressInterp(InterpretationOrigin origin) {
		return this.pressInterps.stream().filter(p -> p.getOrigin().equals(origin) && !p.isNoInterp()).count() > 0;
	}
}
