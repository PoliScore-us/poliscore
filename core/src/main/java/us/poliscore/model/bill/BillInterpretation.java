package us.poliscore.model.bill;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import us.poliscore.model.dynamodb.StructuralAnalysisExplainAttributeConverter;
import us.poliscore.model.dynamodb.StructuralAnalysisPassFailAttributeConverter;
import us.poliscore.model.press.PressInterpretation;

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@DynamoDbBean
@RegisterForReflection
@NoArgsConstructor
@AllArgsConstructor
public class BillInterpretation extends SessionPersistable
{
	public static Logger logger = LoggerFactory.getLogger(BillInterpretation.class);
	
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
		
		if (billId.contains("us/congress/118"))
			id = id.replace("-polisc", "");
		
		return id;
	}
	
	@JsonIgnore
	@Getter(onMethod_ = {@DynamoDbIgnore})
	protected transient Bill bill;
	
	protected IssueStats issueStats;
	
	protected Integer rating;
	
	// Deprecated - use rating instead
	protected Integer quality;
	
	protected Integer confidence;
	
	protected String impactAnalysis;
	
	protected String reasoning;
	
	@JsonIgnore
	@Getter(onMethod_ = {@DynamoDbIgnore})
	protected String structuralAnalysisRaw;
	
	protected String searchReferences;
	
	protected String genBillTitle;
	
	protected List<String> riders;
	
	protected String author;
	
	protected String neutralSummary;
	
	protected String shortExplain;
	
	protected String longExplain;
	
	protected String laymansReport;
	
	@NonNull
	protected String billId;
	
	protected InterpretationOrigin origin = InterpretationOrigin.POLISCORE;
	
	protected List<PressInterpretation> pressInterps = new ArrayList<PressInterpretation>();
	
	protected LocalDate lastPressQuery = LocalDate.EPOCH;
	
	@NonNull 
	@Getter(onMethod = @__({ @DynamoDbIgnore})) // Some bils (such as OBBB) are too large to fit in DDB in one record with all the slices too 
	protected List<BillInterpretation> sliceInterpretations = new ArrayList<BillInterpretation>();
	
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(AIInterpretationMetadataConverter.class)}))
	protected AIInterpretationMetadata metadata;
	
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(StructuralAnalysisPassFailAttributeConverter.class)}))
	protected Map<StructuralAnalysis, Boolean> structuralAnalysisPassFail;
	
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(StructuralAnalysisExplainAttributeConverter.class)}))
	private Map<StructuralAnalysis, String> structuralAnalysisExplain;
	
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
	@DynamoDbIgnore
	public Integer getSliceIndex() {
		if (!(metadata instanceof AISliceInterpretationMetadata)) return null;
		
		return ((AISliceInterpretationMetadata)metadata).getSliceIndex();
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
	
	@JsonIgnore
	@DynamoDbIgnore
	public void validate() {
		if (pressInterps == null || pressInterps.size() == 0) {
			logger.warn("Interpretation had no references! This might not be an error, but it's not ideal either.");
//			throw new InterpretationMissingReferencesException("The interpretation had no references.");
		}
		
		issueStats.validate();
		
		if (StringUtils.isBlank(getLongExplain()))
			throw new RuntimeException("Long explain was blank");
		
		if (getSliceIndex() == null && StringUtils.isBlank(getLaymansReport()))
			throw new RuntimeException("Non-slice interps require laymans report");
		
		if (getSliceIndex() == null && StringUtils.isBlank(getShortExplain()))
			throw new RuntimeException("Non-slice interps require short explain");
		
		if (structuralAnalysisExplain.size() != StructuralAnalysis.values().length)
			throw new RuntimeException("Structural analysis explain missing");
		
		if (structuralAnalysisPassFail.size() != StructuralAnalysis.values().length)
			throw new RuntimeException("Structural analysis pass fail missing");
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public boolean isValid() {
		try {
			validate();
			return true;
		} catch(Throwable t) {
			return false;
		}
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX }) public String getStorageBucket() { return super.getStorageBucket(); }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return metadata.getDate(); }
	@JsonIgnore public void setDate(LocalDate date) { }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return getRating(null); }
	public Integer getRating(TrackedIssue issue) {
		if (rating != null)
			return rating;
		if (quality != null)
			return quality;
		
		return issueStats.getImpact(issue);
	}
	public void setRating(int rating) { }
	
	@JsonIgnore public Integer getImpact(TrackedIssue issue) { return issueStats.getImpact(issue); }
	@JsonIgnore public void setImpact() { }

	public boolean hasPressInterp(InterpretationOrigin origin) {
		return this.pressInterps.stream().filter(p -> p.getOrigin().equals(origin) && !p.isNoInterp()).count() > 0;
	}
}
