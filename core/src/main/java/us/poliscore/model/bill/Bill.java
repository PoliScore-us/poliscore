package us.poliscore.model.bill;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import us.poliscore.legiscan.view.LegiscanState;
import us.poliscore.model.ChamberSize;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.legislator.Legislator.LegislatorName;
import us.poliscore.service.SessionInfoService;

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@RegisterForReflection
@Entity
@Table(name = "bill", indexes = {
		@Index(name = "bill_storage_bucket_idx", columnList = "storage_bucket"),
		@Index(name = "bill_date_idx", columnList = "storage_bucket, date_value, id"),
		@Index(name = "bill_rating_idx", columnList = "storage_bucket, rating_value, id"),
		@Index(name = "bill_rating_abs_idx", columnList = "storage_bucket, rating_abs_value, id"),
		@Index(name = "bill_impact_idx", columnList = "storage_bucket, impact_value, id"),
		@Index(name = "bill_impact_abs_idx", columnList = "storage_bucket, impact_abs_value, id"),
		@Index(name = "bill_hot_idx", columnList = "storage_bucket, hot_value, id")
})
@Access(AccessType.FIELD)
public class Bill extends SessionPersistable {
	
	public static final String ID_CLASS_PREFIX = "BIL";
	
	public static String generateId(LegislativeNamespace ns, String sessionCode, String typeCode, int number)
	{
		return SessionPersistable.generateId(ID_CLASS_PREFIX, ns, sessionCode, typeCode.toLowerCase() + "/" + number);
	}
	public static String generateId(LegislativeNamespace namespace, String sessionKey, CongressionalBillType type, int number) { return generateId(namespace, sessionKey, type.name(), number); }
	
	public static final Double DEFAULT_IMPACT_LAW_WEIGHT = 100.0d;
	
	@JsonIgnore
	@Transient
	protected transient BillText text;
	
	// Type here is sort of overloaded at this point. If it's congressional data, then it will align with CongressionalBillType.name()
	// Otherwise if it's a state bill it should align with LegiscanBillType.getCode()
	protected String type;
	
	@Column(columnDefinition = "integer default 0")
	protected int number;
	
	protected LegislativeChamber originatingChamber;
	
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected BillStatus status;
	
	@Column(columnDefinition = "TEXT")
	protected String name;
	
	@Column(columnDefinition = "integer default 0")
	protected int legiscanId;
	
//	protected String statusUrl;
	
//	protected String textUrl;
	
	// Url to the official bill (i.e. congress or the state website)
	@Column(columnDefinition = "TEXT")
	protected String officialUrl;
	
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected BillSponsor sponsor;
	
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected List<BillSponsor> cosponsors = new ArrayList<BillSponsor>();
	
	protected LocalDate introducedDate;
	
	protected LocalDate lastActionDate;
	
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected BillInterpretation interpretation;

	@JsonIgnore
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "issue_impact_map", columnDefinition = "jsonb")
	protected Map<TrackedIssue, Integer> issueImpactMap = new HashMap<TrackedIssue, Integer>();
	
//	protected List<PressInterpretation> pressInterps;
	
//	protected LocalDate lastPressQuery = LocalDate.EPOCH;
	
	@JsonIgnore
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected CBOBillAnalysis cboAnalysis;

	@JsonIgnore
	@Column(name = "storage_bucket")
	private String storageBucketValue = ID_CLASS_PREFIX;

	@JsonIgnore
	@Column(name = "date_value")
	private String dateValue;

	@JsonIgnore
	@Column(name = "rating_value")
	private Long ratingValue;

	@JsonIgnore
	@Column(name = "rating_abs_value")
	private Long ratingAbsValue;

	@JsonIgnore
	@Column(name = "impact_value")
	private Long impactValue;

	@JsonIgnore
	@Column(name = "impact_abs_value")
	private Long impactAbsValue;

	@JsonIgnore
	@Column(name = "hot_value")
	private Long hotValue;
	
	public void setInterpretation(BillInterpretation interp) {
		this.interpretation = interp;
		
		if (getName() != null && getName().contains(String.valueOf(getNumber())) && !StringUtils.isBlank(interp.getGenBillTitle())) {
			setName(interp.getGenBillTitle());
		}
	}
	
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
		if (StringUtils.isNotBlank(name) && name.length() < 100) {
			return name;
		} else if (interpretation != null && StringUtils.isNotBlank(interpretation.getGenBillTitle())) {
			return interpretation.getGenBillTitle();
		}
		
		return name;
	}

	public void setShortName(String shortName)
	{
		// Derived field exposed for API compatibility. Ignore during deserialization.
	}
	
	@JsonIgnore
	public String getDescription() {
		return getName() + " (" + getType().toLowerCase() + " " + getNumber() + ")";
	}
	
	public LocalDate getLastActionDate() {
		return lastActionDate != null ? lastActionDate : introducedDate;
	}
	
	@JsonIgnore
	public String getWebappUrlPath() {
		return "bill/" + (getNamespace().equals(LegislativeNamespace.US_CONGRESS) ? "" : getSessionCode() + "/") + getType().toLowerCase() + "/" + getNumber();
	}
	
	public boolean isIntroducedInSession(LegislativeSession session) {
		return session.getCode().equals(getSessionCode()) && this.getNamespace().equals(session.getNamespace());
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX, Persistable.OBJECT_BY_RATING_ABS_INDEX, Persistable.OBJECT_BY_IMPACT_INDEX, OBJECT_BY_IMPACT_ABS_INDEX, OBJECT_BY_HOT_INDEX })
	public String getStorageBucket() {
		if (getNamespace().equals(LegislativeNamespace.US_CONGRESS))
			return this.getId().substring(0, StringUtils.ordinalIndexOf(getId(), "/", 4));
		else {
			return ID_CLASS_PREFIX + "/" + getNamespace() + "/" + String.valueOf(SessionInfoService.lookupRegularSession(getNamespace(), getSessionCode()).getEndDate().getYear());
		}
	}
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() {
//		if (lastActionDate != null) return lastActionDate;
		
		return introducedDate;
	}
	@JsonIgnore public void setDate(LocalDate date) { introducedDate = date; }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return interpretation.getRating(); }
	@JsonIgnore public void setRating(int rating) { }
	@JsonIgnore public int getRating(TrackedIssue issue) { return interpretation.getRating(issue); }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_ABS_INDEX }) public int getRatingAbs() { return Math.abs(interpretation.getRating()); }
	@JsonIgnore public void setRatingAbs(int rating) { }

	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_IMPACT_INDEX })
	public int getImpact() { return (int)(getImpact(TrackedIssue.OverallBenefitToSociety, status.getDescription().toLowerCase().trim().equals("law") ? DEFAULT_IMPACT_LAW_WEIGHT : 0.0d) * Math.exp(-0.015 * ChronoUnit.DAYS.between(getLastActionDate(), LocalDate.now()))); }
	public void setImpact(int impact) { }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_IMPACT_ABS_INDEX })
	public int getImpactAbs() { return (int)(Math.abs(getImpact()) * Math.exp(-0.015 * ChronoUnit.DAYS.between(getLastActionDate(), LocalDate.now()))); }
	@JsonIgnore public int getImpactAbs(TrackedIssue issue, double lawWeight) { return Math.abs(getImpact(issue, lawWeight)); }
	public void setImpactAbs(int impact) { }
	
	// TODO : Maybe don't completely hide "final" statuses here, but make them decay super fast
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_HOT_INDEX }) public int getHot() { if (status.getDescription().toLowerCase().startsWith("vetoed")) return 0; else return (int) (getImpactAbs(TrackedIssue.OverallBenefitToSociety, 0.0d) * Math.exp(-0.015 * ChronoUnit.DAYS.between(getLastActionDate(), LocalDate.now()))); }
	public void setHot(int hot) { }
	
	@JsonIgnore public int getImpact(TrackedIssue issue) { return getImpact(issue, DEFAULT_IMPACT_LAW_WEIGHT); };
	
	@JsonIgnore public int getImpact(TrackedIssue issue, double lawWeight)
	{
		return calculateImpact(interpretation.getIssueStats().getStat(issue), status.getProgress(), getCosponsorPercent(), lawWeight);
	}

	public static int calculateImpact(int interpImpact, float statusProgress, float cosponsorPercent)
	{
		// 100 is the default 'lawWeight' for impact, and this is because when it comes to legislators, we want the legislator with the most sponsored
		// laws to massively outweigh a legislator that otherwise just voted on the most bills. There is one specific scenario where we want the weight
		// to be calculated differently, however, and that is when calculating the bill 'hot' index. In that scenario, we want laws to be important, but
		// not always outweigh everything else, as we want the date to be a factor which sometimes outweighs the law weight.
		return calculateImpact(interpImpact, statusProgress, cosponsorPercent, DEFAULT_IMPACT_LAW_WEIGHT);
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

	@JsonIgnore
	public Map<TrackedIssue, Integer> getIssueImpactMap()
	{
		if ((issueImpactMap == null || issueImpactMap.isEmpty()) && canCalculateDerivedMetrics()) {
			refreshIssueImpactMap();
		}

		return issueImpactMap;
	}
	
	@Data
	@RequiredArgsConstructor
	@NoArgsConstructor
	@AllArgsConstructor
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
	@RequiredArgsConstructor
	@NoArgsConstructor
	public static class BillSponsorOld {
		
		@JsonIgnore
		@Getter
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

	@Override
	protected void synchronizeJpaState()
	{
		storageBucketValue = getStorageBucket();
		dateValue = getDate() == null ? null : getDate().toString();
		refreshIssueImpactMap();
		ratingValue = interpretation == null || interpretation.getRating() == null ? null : Long.valueOf(interpretation.getRating());
		ratingAbsValue = ratingValue == null ? null : Math.abs(ratingValue);
		impactValue = canCalculateDerivedMetrics() ? Long.valueOf(getImpact()) : null;
		impactAbsValue = canCalculateDerivedMetrics() ? Long.valueOf(getImpactAbs()) : null;
		hotValue = canCalculateDerivedMetrics() ? Long.valueOf(getHot()) : null;
	}

	private boolean canCalculateDerivedMetrics()
	{
		return interpretation != null && status != null && getLastActionDate() != null;
	}

	private void refreshIssueImpactMap()
	{
		if (issueImpactMap == null) {
			issueImpactMap = new HashMap<TrackedIssue, Integer>();
		} else {
			issueImpactMap.clear();
		}

		if (!canCalculateDerivedMetrics() || interpretation.getIssueStats() == null) {
			return;
		}

		for (TrackedIssue issue : TrackedIssue.values()) {
			issueImpactMap.put(issue, getImpact(issue));
		}
	}
}
