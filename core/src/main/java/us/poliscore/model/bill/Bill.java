package us.poliscore.model.bill;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import us.poliscore.PoliscoreUtil;
import us.poliscore.legiscan.view.LegiscanState;
import us.poliscore.model.ChamberSize;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.legislator.Legislator.LegislatorName;

@Data
@DynamoDbBean
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@RegisterForReflection
public class Bill implements Persistable {
	
	public static final String ID_CLASS_PREFIX = "BIL";
	
	public static final Double DEFAULT_IMPACT_LAW_WEIGHT = 100.0d;
	
	/**
	 * An optional grouping mechanism, beyond the ID_CLASS_PREFIX concept, which allows you to group objects of the same class in different
	 * "storage buckets". Really only used in DynamoDb at the moment, and is used for querying on the object indexes with objects that exist
	 * in different congressional sessions.
	 */
	public static String getClassStorageBucket(LegislativeNamespace namespace, String sessionKey)
	{
		return ID_CLASS_PREFIX + "/" + namespace.getNamespace() + "/" + sessionKey;
	}
	
	@JsonIgnore
	protected transient BillText text;
	
	// Type here is sort of overloaded at this point. If it's congressional data, then it will align with CongressionalBillType.name()
	// Otherwise if it's a state bill it should align with LegiscanBillType.getCode()
	protected String type;
	
	protected int number;
	
	protected LegislativeChamber originatingChamber;
	
	protected BillStatus status;
	
	protected String name;
	
	protected String id;
	
	protected int legiscanId;
	
//	protected String statusUrl;
	
//	protected String textUrl;
	
	protected BillSponsor sponsor;
	
	protected List<BillSponsor> cosponsors = new ArrayList<BillSponsor>();
	
	protected LocalDate introducedDate;
	
	protected LocalDate lastActionDate;
	
	protected BillInterpretation interpretation;
	
//	protected List<PressInterpretation> pressInterps;
	
//	protected LocalDate lastPressQuery = LocalDate.EPOCH;
	
	@JsonIgnore
	protected CBOBillAnalysis cboAnalysis;
	
	public void setInterpretation(BillInterpretation interp) {
		this.interpretation = interp;
		
		if (getName() != null && getName().contains(String.valueOf(getNumber())) && !StringUtils.isBlank(interp.getGenBillTitle())) {
			setName(interp.getGenBillTitle());
		}
	}
	
	@DynamoDbIgnore
	@JsonIgnore
	public BillText getText()
	{
		return text;
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public String getPoliscoreId()
	{
		return id;
	}
	
//	@JsonIgnore
//	public String getUSCId()
//	{
//		return type.getName().toLowerCase() + number + "-" + session;
//	}
	
	public String getShortName()
	{
		if (StringUtils.isNotBlank(name) && name.length() < 125) {
			return name;
		} else if (interpretation != null && StringUtils.isNotBlank(interpretation.getGenBillTitle())) {
			return interpretation.getGenBillTitle();
		}
		
		return name;
	}
	
	@DynamoDbPartitionKey
	@EqualsAndHashCode.Include
	public String getId()
	{
		return getPoliscoreId();
	}
	
	public void setId(String id) { this.id = id; }
	
	@JsonIgnore
	@DynamoDbIgnore
	public LegislativeNamespace getNamespace() {
		return LegislativeNamespace.of(this.id.split("/")[1] + "/" + this.id.split("/")[2]);
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public String getSessionCode() {
		return this.id.split("/")[3];
	}
	
	public boolean isIntroducedInSession(LegislativeSession session) {
		return session.getCode().equals(getSessionCode()) && this.getNamespace().equals(session.getNamespace());
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX, Persistable.OBJECT_BY_RATING_ABS_INDEX, Persistable.OBJECT_BY_IMPACT_INDEX, OBJECT_BY_IMPACT_ABS_INDEX, OBJECT_BY_HOT_INDEX }) public String getStorageBucket() {
		return this.getId().substring(0, StringUtils.ordinalIndexOf(getId(), "/", 4));
	}
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() {
		if (lastActionDate != null) return lastActionDate;
		
		return introducedDate;
	}
	@JsonIgnore public void setDate(LocalDate date) { introducedDate = date; }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return interpretation.getRating(); }
	@JsonIgnore public void setRating(int rating) { }
	@JsonIgnore public int getRating(TrackedIssue issue) { return interpretation.getRating(issue); }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_ABS_INDEX }) public int getRatingAbs() { return Math.abs(interpretation.getRating()); }
	@JsonIgnore public void setRatingAbs(int rating) { }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_IMPACT_INDEX }) public int getImpact() { return getImpact(TrackedIssue.OverallBenefitToSociety); }
	public void setImpact(int impact) { }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_IMPACT_ABS_INDEX }) public int getImpactAbs() { return Math.abs(getImpact()); }
	@JsonIgnore public int getImpactAbs(TrackedIssue issue, double lawWeight) { return Math.abs(getImpact(issue, lawWeight)); }
	public void setImpactAbs(int impact) { }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_HOT_INDEX }) public int getHot() { return (int)(getImpactAbs(TrackedIssue.OverallBenefitToSociety, 1.5d) * Math.exp(-0.02 * ChronoUnit.DAYS.between(getDate(), LocalDate.now()))); }
	public void setHot(int hot) { }
	
	public static String generateId(LegislativeNamespace namespace, String sessionCode, String typeCode, int number)
	{
		return ID_CLASS_PREFIX + "/" + namespace.getNamespace() + "/" + sessionCode + "/" + typeCode.toLowerCase() + "/" + number;
	}
	public static String generateId(LegislativeNamespace namespace, String sessionKey, CongressionalBillType type, int number) { return generateId(namespace, sessionKey, type.name(), number); }
	
	@JsonIgnore public int getImpact(TrackedIssue issue) { return getImpact(issue, DEFAULT_IMPACT_LAW_WEIGHT); };
	
	@JsonIgnore public int getImpact(TrackedIssue issue, double lawWeight)
	{
		return calculateImpact(interpretation.getIssueStats().getStat(issue), status.getProgress(), getCosponsorPercent(), lawWeight);
	}

	public static int calculateImpact(int rating, float statusProgress, float cosponsorPercent)
	{
		// 100 is the default 'lawWeight' for impact, and this is because when it comes to legislators, we want the legislator with the most sponsored
		// laws to massively outweigh a legislator that otherwise just voted on the most bills. There is one specific scenario where we want the weight
		// to be calculated differently, however, and that is when calculating the bill 'hot' index. In that scenario, we want laws to be important, but
		// not always outweigh everything else, as we want the date to be a factor which sometimes outweighs the law weight.
		return calculateImpact(rating, statusProgress, cosponsorPercent, DEFAULT_IMPACT_LAW_WEIGHT);
	}
	
	public static int calculateImpact(int rating, float statusProgress, float cosponsorPercent, double lawWeight)
	{
		double statusTerm = statusProgress*100000d * (statusProgress == 1.0f ? lawWeight : 1d);
		double ratingTerm = Math.abs((double)rating/100f)*10000d;
		double cosponsorTerm = cosponsorPercent*1000d;
		int sign = rating < 0 ? -1 : 1;
		
		return (int) Math.round(statusTerm + ratingTerm + cosponsorTerm ) * sign;
	}
	
	public LegislativeChamber getOriginatingChamber()
	{
		if (originatingChamber == null && getNamespace().equals(LegislativeNamespace.US_CONGRESS)) {
			return CongressionalBillType.getOriginatingChamber(CongressionalBillType.valueOf(type));
		} else {
			return originatingChamber;
		}
	}
	
	/*
	 * A percentage of how much of the chamber has cosponsored the bill.
	 */
	public float getCosponsorPercent()
	{
		if (getOriginatingChamber() == null) throw new UnsupportedOperationException("Originating chamber is null for " + getId());
		
		float percent;
		float chamberSize = ((float)ChamberSize.getChamberSize(LegiscanState.fromAbbreviation(getNamespace().toAbbreviation()), getOriginatingChamber()));
		
		percent = (float)cosponsors.size() / chamberSize;
		
		return percent;
	}

	
	public static CongressionalBillType billTypeFromId(String poliscoreId) {
		return CongressionalBillType.fromName(poliscoreId.split("/")[4]);
	}
	
	public static int billNumberFromId(String poliscoreId) {
		return Integer.valueOf(poliscoreId.split("/")[5]);
	}
	
	@Data
	@DynamoDbBean
	@RequiredArgsConstructor
	@NoArgsConstructor
	public static class BillSponsor {
		
//		@JsonIgnore
//		@Getter(onMethod = @__({ @DynamoDbIgnore }))
//		protected String bioguide_id;
		
		@NonNull
		protected String legislatorId;
		
		protected Party party;
		
		@NonNull
		protected LegislatorName name;
		
		@JsonIgnore
		public String getId() {
			return legislatorId;
		}
		
	}
	
	@Data
	@DynamoDbBean
	@RequiredArgsConstructor
	@NoArgsConstructor
	public static class BillSponsorOld {
		
		@JsonIgnore
		@Getter(onMethod = @__({ @DynamoDbIgnore }))
		protected String bioguide_id;
		
		@NonNull
		protected String legislatorId;
		
		protected Party party;
		
		@NonNull
		protected String name;
		
		@JsonIgnore
		public String getId() {
			return legislatorId;
		}
		
	}
}
