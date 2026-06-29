package us.poliscore.model.bill;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
import us.poliscore.model.AIAggregateInterpretationMetadata;
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
import us.poliscore.util.ParsingUtil;

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@JsonIgnoreProperties(value = { "sliceInterpretations" }, ignoreUnknown = true)
@DynamoDbBean
@RegisterForReflection
@NoArgsConstructor
@AllArgsConstructor
public class BillInterpretation extends SessionPersistable
{
	public static Logger logger = LoggerFactory.getLogger(BillInterpretation.class);
	
	public static final String ID_CLASS_PREFIX = "BIT";

	public static String generateId(String billId, String billTextVersion, Integer sliceIndex)
	{
		if (billId.contains("us/congress/118") || StringUtils.isBlank(billTextVersion)) {
			return generateLegacyId(billId, InterpretationOrigin.POLISCORE, sliceIndex);
		}

		var id = billId.replace(Bill.ID_CLASS_PREFIX, ID_CLASS_PREFIX) + "/" + billTextVersion;

		if (sliceIndex != null) {
			id += "/" + sliceIndex;
		}

		return id;
	}
	
	private static String generateLegacyId(String billId, InterpretationOrigin origin, Integer sliceIndex)
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

	public static ParsedId parseId(String id)
	{
		if (!id.startsWith(ID_CLASS_PREFIX + "/")) {
			throw new IllegalArgumentException("Not a BillInterpretation id: " + id);
		}

		String billLikeId = Bill.ID_CLASS_PREFIX + id.substring(ID_CLASS_PREFIX.length());
		String[] parts = billLikeId.split("/");

		if (parts.length < 6) {
			throw new IllegalArgumentException("Invalid BillInterpretation id: " + id);
		}

		if (parts.length > 6) {
			String billId = String.join("/", Arrays.copyOfRange(parts, 0, 6));
			String billTextVersion = parts[6];
			Integer sliceIndex = parts.length > 7 ? Integer.valueOf(parts[7]) : null;
			return new ParsedId(billId, billTextVersion, sliceIndex);
		}

		String[] legacyTail = parts[5].split("-");
		String billId = String.join("/", Arrays.copyOfRange(parts, 0, 5)) + "/" + legacyTail[0];
		Integer sliceIndex = null;

		if (legacyTail.length > 1 && StringUtils.isNumeric(legacyTail[legacyTail.length - 1])) {
			sliceIndex = Integer.valueOf(legacyTail[legacyTail.length - 1]);
		}

		return new ParsedId(billId, null, sliceIndex);
	}

	public record ParsedId(String billId, String sourceBillTextVersion, Integer sliceIndex) { }
	
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

	protected String sourceBillTextVersion;

	/**
	 * The first time this bill-text version was interpreted. Unlike lastUpdate,
	 * this value is preserved when the deterministic interpretation object is
	 * regenerated.
	 */
	protected LocalDateTime firstGeneratedAt;

	public void recordGeneration(LocalDateTime generatedAt, BillInterpretation existingInterpretation) {
		LocalDateTime existingFirstGeneratedAt = existingInterpretation == null
				? null
				: existingInterpretation.getFirstGeneratedAt();
		if (existingFirstGeneratedAt == null && existingInterpretation != null) {
			existingFirstGeneratedAt = existingInterpretation.getLastUpdate();
		}

		setFirstGeneratedAt(existingFirstGeneratedAt == null ? generatedAt : existingFirstGeneratedAt);
		setLastUpdate(generatedAt);
	}
	
	protected List<String> topics;
	
	protected List<String> otherNames;
	
	@NonNull
	protected String billId;
	
	protected InterpretationOrigin origin = InterpretationOrigin.POLISCORE;
	
	protected List<PressInterpretation> pressInterps = new ArrayList<PressInterpretation>();
	
	protected LocalDate lastPressQuery = LocalDate.EPOCH;
	
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
	public void validate(boolean isParser) {
		if (pressInterps == null || pressInterps.size() == 0) {
			logger.warn("Interpretation had no references! This might not be an error, but it's not ideal either.");
//			throw new InterpretationMissingReferencesException("The interpretation had no references.");
		}
		
		if (!(isParser && getMetadata() instanceof AIAggregateInterpretationMetadata))
			issueStats.validate();
		
		ParsingUtil.strValidate(getLongExplain(), "long explain", 15_000);
		
		if (getSliceIndex() == null) {
			ParsingUtil.strValidate(getLaymansReport(), "casual report", 6000);
			ParsingUtil.strValidate(getShortExplain(), "short explain", 3000);
		}
		
		if (structuralAnalysisExplain.size() != StructuralAnalysis.values().length)
			throw new RuntimeException("Structural analysis explain missing");
		
		if (structuralAnalysisPassFail.size() != StructuralAnalysis.values().length)
			throw new RuntimeException("Structural analysis pass fail missing");
		
		for (var sa : StructuralAnalysis.values()) {
			ParsingUtil.strValidate(structuralAnalysisExplain.get(sa), "structural analysis pillar [" + sa.name() + "]", 3000);
			
			if (structuralAnalysisPassFail.get(sa) == null)
				throw new RuntimeException("Structural analysis pillar [" + sa.name() + "] pass/faill was null");
		}
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public boolean isValid() {
		try {
			validate(false);
			return true;
		} catch(Throwable t) {
			return false;
		}
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX }) public String getStorageBucket() { return super.getStorageBucket(); }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return metadata.getDate(); }
	@JsonIgnore public void setDate(LocalDate date) { }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public Integer getRating() { return getRating(null); }
	public Integer getRating(TrackedIssue issue) {
		if (rating != null)
			return rating;
		if (quality != null)
			return quality;
		if (issueStats != null)
			return issueStats.getImpact(issue);
		
		return null;
	}
	public void setRating(Integer rating) { }
	
	@JsonIgnore public Integer getImpact(TrackedIssue issue) { return issueStats.getImpact(issue); }
	@JsonIgnore public void setImpact() { }

	public boolean hasPressInterp(InterpretationOrigin origin) {
		return this.pressInterps.stream().filter(p -> p.getOrigin().equals(origin) && !p.isNoInterp()).count() > 0;
	}
}
