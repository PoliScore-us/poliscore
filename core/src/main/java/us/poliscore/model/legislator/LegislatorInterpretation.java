package us.poliscore.model.legislator;

import java.time.LocalDate;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.IssueStats;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;
import us.poliscore.model.StructuralStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.util.ParsingUtil;

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@DynamoDbBean
@NoArgsConstructor
@RegisterForReflection
public class LegislatorInterpretation extends SessionPersistable
{
	public static final String ID_CLASS_PREFIX = "LIT";
	
	public static String generateId(LegislativeNamespace ns, String regularSessionCode, String legislatorCode)
	{
		return SessionPersistable.generateId(ID_CLASS_PREFIX, ns, regularSessionCode, legislatorCode);
	}
	
	protected IssueStats issueStats;
	
	protected StructuralStats structuralStats;
	
	protected Integer rating;
	
	protected AIInterpretationMetadata metadata;
	
	protected int hash;
	
	protected String shortExplain;
	
	protected String casualExplain;

	protected String longExplain;
	
	protected String reasoning;
	
	protected String references;
	
	public LegislatorInterpretation(LegislativeNamespace namespace, String sessionKey, String legislatorCode, AIInterpretationMetadata metadata, IssueStats stats)
	{
		this.id = generateId(namespace, sessionKey, legislatorCode);
		this.metadata = metadata;
		this.issueStats = stats;
	}
	
	public void validate()
	{
		if (this.getLastUpdate() != null && this.getLastUpdate().isBefore(LocalDate.of(2026, 04, 01).atStartOfDay()))
			return;
		
		ParsingUtil.strValidate(getLongExplain(), "long explain", 15_000);
		ParsingUtil.strValidate(getCasualExplain(), "casual report", 6000);
		ParsingUtil.strValidate(getShortExplain(), "short explain", 3000);
	
		if (getIssueStats() == null || !getIssueStats().hasStat(TrackedIssue.OverallBenefitToSociety)) {
			throw new IllegalStateException("Unable to parse valid issue stats for " + getId());
		}
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public String getLegislatorId()
	{
		return this.id.replaceFirst("^" + ID_CLASS_PREFIX, Legislator.ID_CLASS_PREFIX);
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX }) public String getStorageBucket() { return super.getStorageBucket(); }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return metadata.getDate(); }

	@JsonIgnore public void setDate(LocalDate date) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public Integer getRating() { return getRating(null); }
	public Integer getRating(TrackedIssue issue) {
		if (rating != null)
			return rating;
		
		if (issueStats != null)
			return issueStats.getImpact(issue);
		
		return null;
	}

	@JsonIgnore public void setRating(Integer rating) { }
}
