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
import us.poliscore.legiscan.view.LegiscanState;
import us.poliscore.model.ChamberSize;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.Legislator.LegislatorName;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@DynamoDbBean
@RegisterForReflection
public class Bill extends SessionPersistable {
	
	public static final String ID_CLASS_PREFIX = "BIL";
	
	public static String generateId(LegislativeNamespace ns, String sessionCode, String typeCode, int number)
	{
		return SessionPersistable.generateId(ID_CLASS_PREFIX, ns, sessionCode, typeCode.toLowerCase() + "/" + number);
	}
	public static String generateId(LegislativeNamespace namespace, String sessionKey, CongressionalBillType type, int number) { return generateId(namespace, sessionKey, type.name(), number); }
	
	public static final Double DEFAULT_IMPACT_LAW_WEIGHT = 100.0d;
	
	@JsonIgnore
	protected transient BillText text;
	
	// Type here is sort of overloaded at this point. If it's congressional data, then it will align with CongressionalBillType.name()
	// Otherwise if it's a state bill it should align with LegiscanBillType.getCode()
	protected String type;
	
	protected int number;
	
	protected LegislativeChamber originatingChamber;
	
	protected BillStatus status;
	
	protected String name;
	
	protected int legiscanId;
	
//	protected String statusUrl;
	
//	protected String textUrl;
	
	// Url to the official bill (i.e. congress or the state website)
	protected String officialUrl;
	
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
	
	public boolean isIntroducedInSession(LegislativeSession session) {
		return session.getCode().equals(getSessionCode()) && this.getNamespace().equals(session.getNamespace());
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX, Persistable.OBJECT_BY_RATING_ABS_INDEX, Persistable.OBJECT_BY_IMPACT_INDEX, OBJECT_BY_IMPACT_ABS_INDEX, OBJECT_BY_HOT_INDEX }) public String getStorageBucket() { return super.getStorageBucket(); }
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
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_HOT_INDEX }) public int getHot() { return (int)(getImpactAbs(TrackedIssue.OverallBenefitToSociety, 1.5d) * Math.exp(-0.015 * ChronoUnit.DAYS.between(getDate(), LocalDate.now()))); }
	public void setHot(int hot) { }
	
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
