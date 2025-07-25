package us.poliscore.model.legislator;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
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
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.LegislatorLegislativeTermSortedSetConverter;
import us.poliscore.model.legislator.Legislator.LegislatorLegislativeTermSortedSet;
import us.poliscore.model.legislator.Legislator.LegislatorName;

@Data
@DynamoDbBean
@RegisterForReflection
@NoArgsConstructor
public class LegislatorIssueStat implements Persistable {
	public static final String ID_CLASS_PREFIX = "LIS";
	
	public static String getIndexPrimaryKey(LegislativeNamespace namespace, String sessionCode, TrackedIssue issue)
	{
		return ID_CLASS_PREFIX + "/" + namespace.getNamespace() + "/" + sessionCode + "/" + issue.name();
	}
	
	public LegislatorIssueStat(TrackedIssue issue, long impact, Legislator leg) {
		this.issue = issue;
		this.impact = impact;
		this.legislatorId = leg.getId();
		this.name = leg.getName();
		this.interpretation = leg.getInterpretation();
		this.terms = leg.getTerms();
	    this.rating = leg.getRating(issue);
	}
	
	protected TrackedIssue issue;
	
	@Getter(onMethod = @__({ @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX }) }))
	protected long impact;
	
	protected String legislatorId;
	
	protected LegislatorName name;
	
	LegislatorInterpretation interpretation;
	
	@Getter(onMethod = @__({ @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_ISSUE_RATING_INDEX }) }))
	protected int rating;
	
	@NonNull
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(LegislatorLegislativeTermSortedSetConverter.class) }))
	protected LegislatorLegislativeTermSortedSet terms;
	
	@JsonIgnore @DynamoDbIgnore public String getSession() { return legislatorId.split("/")[3]; }
	@JsonIgnore @DynamoDbIgnore public LegislativeNamespace getNamespace() { return LegislativeNamespace.of(legislatorId.split("/")[1] + "/" + legislatorId.split("/")[2]); }
	
	@DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX, Persistable.OBJECT_BY_ISSUE_RATING_INDEX })
	@JsonIgnore public String getIssuePK() { return ID_CLASS_PREFIX + "/" + getNamespace().getNamespace() + "/" + getSession() + "/" + issue.name(); }
	@JsonIgnore public void setIssuePK(String pk) { }
	
	@DynamoDbPartitionKey
	@JsonIgnore public String getId() { return getIssuePK() + "/" + legislatorId; }
	@JsonIgnore public void setId(String id) { }
	
	@JsonIgnore public String getStorageBucket() { return null; }
	@JsonIgnore public void setStorageBucket(String prefix) { }
}
