package us.poliscore.model.legislator;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import us.poliscore.model.IssueStats;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.VoteStatus;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.dynamodb.DdbKeyProvider;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillCosponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillSponsor;
import us.poliscore.model.legislator.LegislatorBillInteraction.LegislatorBillVote;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = LegislatorBillVote.class, name = "LegislatorBillVote"),
    @JsonSubTypes.Type(value = LegislatorBillSponsor.class, name = "LegislatorBillSponsor"),
	@JsonSubTypes.Type(value = LegislatorBillCosponsor.class, name = "LegislatorBillCosponsor") }
)
@Data
@RequiredArgsConstructor
@AllArgsConstructor
@NoArgsConstructor
@RegisterForReflection
@EqualsAndHashCode
@DynamoDbBean
public abstract class LegislatorBillInteraction implements Comparable<LegislatorBillInteraction>, Persistable {
	
	public static final String ID_CLASS_PREFIX = "LBI";
	
	@NonNull
	protected String legId;
	
	@NonNull
	protected String billId;
	
	@NonNull
	protected String billName;
	
	@EqualsAndHashCode.Exclude
	protected float statusProgress;
	
	@EqualsAndHashCode.Exclude
	protected float cosponsorPercent;
	
//	@NonNull
//	protected String billDescription;
	
	@EqualsAndHashCode.Exclude
	protected IssueStats issueStats;
	
	@EqualsAndHashCode.Exclude
	protected String shortExplain;
	
	@NonNull
	@EqualsAndHashCode.Exclude
	@Getter(onMethod = @__({ @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) }))
	protected LocalDate date;
	
	@JsonIgnore
	@DynamoDbIgnore
	abstract public float getJudgementWeight();
	
	@JsonIgnore
	@DynamoDbIgnore
	abstract public String describe();
	
	@Override
	public int compareTo(LegislatorBillInteraction interact) {
		return interact.date.compareTo(this.date);
	}
	
	public boolean supercedes(LegislatorBillInteraction similar)
	{
		return this.equals(similar) && date.isAfter(similar.getDate());
	}
	
	public static String generateId(String legId, LocalDate date, String billId) {
		return generatePartitionKey(legId) + "~" + generateSortKey(date, billId);
	}
	
	public static String generatePartitionKey(String legId) {
		return legId.replace(Legislator.ID_CLASS_PREFIX, ID_CLASS_PREFIX);
	}
	
	public static String generateSortKey(LocalDate date, String billId) {
		var idParts = Arrays.asList(billId.split("/"));
		return date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
				+ "/"
				+ StringUtils.join(idParts.subList(2, idParts.size()), "/");
	}
	
	@DdbKeyProvider
	public static Key ddbKey(String id) {
		return Key.builder().partitionValue(id.split("~")[0]).sortValue(id.split("~")[1]).build();
	}
	
	public void populate(Bill bill, BillInterpretation interp)
	{
		this.setIssueStats(interp.getIssueStats());
		this.setShortExplain(interp.getShortExplain());
		this.setBillName(bill.getShortName());
		this.setStatusProgress(bill.getStatus().getProgress());
		this.setCosponsorPercent(bill.getCosponsorPercent());
	}
	
	@DynamoDbPartitionKey
	@JsonIgnore
	public String getPartitionKey()
	{
		return generatePartitionKey(legId);
	}
	public void setPartitionKey(String key) { }
	
	@DynamoDbSortKey
	@JsonIgnore
	public String getSortKey()
	{
		return generateSortKey(date, billId);
	}
	public void setSortKey(String key) { }
	
	@JsonIgnore
	public String getId()
	{
		return generateId(legId, date, billId);
	}
	public void setId(String id) { }
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX, Persistable.OBJECT_BY_IMPACT_INDEX, Persistable.OBJECT_BY_IMPACT_ABS_INDEX }) public String getStorageBucket() { return ID_CLASS_PREFIX; }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return getRating(TrackedIssue.OverallBenefitToSociety); }
	@JsonIgnore public void setRating(int rating) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_ABS_INDEX }) public int getRatingAbs() { return Math.abs(getRating()); }
	@JsonIgnore public void setRatingAbs(int rating) { }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_IMPACT_INDEX })
	@JsonIgnore public int getImpact() { return getImpact(TrackedIssue.OverallBenefitToSociety); }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_IMPACT_ABS_INDEX })
	@JsonIgnore public int getImpactAbs() { return Math.abs(getImpact()); }
	public void setImpactAbs(int impact) { }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_HOT_INDEX }) public int getHot() { return (int)(getImpactAbs() * Math.exp(-0.02 * ChronoUnit.DAYS.between(date, LocalDate.now()))); }
	public void setHot(int hot) { }
	
	@JsonIgnore public int getOverallImpact() { return getImpact(); }
	@JsonIgnore public int getOverallRating() { return getRating(); }
	
	/**
	 * Returns a rating multiplied by the judgement weight
	 */
	@JsonIgnore public int getWeightedRating()
	{
//		return calculateWeightedRating(getRating(), statusProgress, this.getJudgementWeight());
		
		return (int) Math.round(getRating() * getJudgementWeight());
	}
	
//	public static int calculateWeightedRating(int rating, float statusProgress, float judgementWeight)
//	{
//		double statusTerm = statusProgress * 0.25d;
//		double ratingTerm = (((double)rating + 100d) / 200d) * 1.0d;
//		double judgementTerm = ((judgementWeight + 0.5d) / 1.5d) * 0.5d;
//		
//		return (int) Math.round(statusTerm + ratingTerm + judgementTerm );
//	}
	
	public int getImpact(TrackedIssue issue)
	{
		return Math.round( (float)Bill.calculateImpact(issueStats.getStat(issue), statusProgress, cosponsorPercent) * getJudgementWeight() );
	}
	
	public int getRating(TrackedIssue issue)
	{
		return Math.round(issueStats.getStat(issue) * getJudgementWeight());
	}
	
	@Data
	@EqualsAndHashCode(callSuper=true)
	@RegisterForReflection
	@AllArgsConstructor
	@NoArgsConstructor
	@DynamoDbBean
	public static class LegislatorBillVote extends LegislatorBillInteraction {
		
		@NonNull
		protected VoteStatus voteStatus;
		
		@DynamoDbIgnore
		@JsonIgnore
		public float getJudgementWeight() { return voteStatus.equals(VoteStatus.NAY) ? -0.5f : 0.5f; }
		
		public String describe() { return "Voted " + voteStatus.describe(); }
		
	}
	
	@Data
	@EqualsAndHashCode(callSuper=true)
	@RegisterForReflection
	@NoArgsConstructor
	@DynamoDbBean
	public static class LegislatorBillSponsor extends LegislatorBillInteraction {
		
		@DynamoDbIgnore
		@JsonIgnore
		public float getJudgementWeight() { return 1.0f; }
		
		public String describe() { return "Sponsor"; }
		
	}
	
	@Data
	@EqualsAndHashCode(callSuper=true)
	@RegisterForReflection
	@NoArgsConstructor
	@DynamoDbBean
	public static class LegislatorBillCosponsor extends LegislatorBillInteraction {
		
		@DynamoDbIgnore
		@JsonIgnore
		public float getJudgementWeight() { return 0.7f; }
		
		public String describe() { return "Cosponsor"; }
		
	}
	
}
