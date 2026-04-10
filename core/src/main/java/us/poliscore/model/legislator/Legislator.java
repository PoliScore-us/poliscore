package us.poliscore.model.legislator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import us.poliscore.legiscan.view.LegiscanState;
import us.poliscore.legiscan.view.LegiscanState.LegiscanStateDeserializer;
import us.poliscore.legiscan.view.LegiscanState.LegiscanStateSerializer;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.dynamodb.IssueStatsMapLongAttributeConverter;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.LegislatorLegislativeTermSortedSetConverter;
import us.poliscore.model.dynamodb.LegiscanStateConverter;

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@RegisterForReflection
@Entity
@Table(name = "legislator", indexes = {
		@Index(name = "legislator_storage_bucket_idx", columnList = "storage_bucket"),
		@Index(name = "legislator_date_idx", columnList = "storage_bucket, date_value, id"),
		@Index(name = "legislator_rating_idx", columnList = "storage_bucket, rating_value, id"),
		@Index(name = "legislator_rating_abs_idx", columnList = "storage_bucket, rating_abs_value, id"),
		@Index(name = "legislator_location_idx", columnList = "storage_bucket, location_value, id"),
		@Index(name = "legislator_impact_idx", columnList = "storage_bucket, impact_value, id"),
		@Index(name = "legislator_impact_abs_idx", columnList = "storage_bucket, impact_abs_value, id")
})
@Access(AccessType.FIELD)
public class Legislator extends SessionPersistable implements Comparable<Legislator> {
	
	public static final String ID_CLASS_PREFIX = "LEG";

	public static final LocalDate DEFAULT_BIRTHDAY = LocalDate.of(1970, 1, 1);
	
	public static String generateId(LegislativeNamespace ns, String regularSessionCode, String legislatorCode)
	{
		return SessionPersistable.generateId(ID_CLASS_PREFIX, ns, regularSessionCode, legislatorCode);
	}
	
	@NonNull
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected LegislatorName name;
	
	// Url to the official bill (i.e. congress or the state website)
	@Column(columnDefinition = "TEXT")
	protected String officialUrl;
	
	// Senate Id (only used in congress) : https://github.com/usgpo/bill-status/issues/241
	protected String lisId;
	
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected LegislatorInterpretation interpretation;
	
	@Getter(onMethod = @__({ @JsonIgnore }))
	protected Integer legiscanId;
	
	protected LocalDate birthday = null;
	
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(IssueStatsMapLongAttributeConverter.class) }))
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	public Map<TrackedIssue, Long> impactMap = new HashMap<TrackedIssue, Long>();
	
	@NonNull
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(LegislatorLegislativeTermSortedSetConverter.class) }))
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected TreeSet<LegislativeTerm> terms = new TreeSet<LegislativeTerm>();
	
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "interactions", columnDefinition = "jsonb")
	private List<LegislatorBillInteraction> interactionsAll = new ArrayList<LegislatorBillInteraction>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "interactions_first_page", columnDefinition = "jsonb")
	private List<LegislatorBillInteraction> interactionsFirstPage = new ArrayList<LegislatorBillInteraction>();

	@JsonProperty
	public List<LegislatorBillInteraction> getInteractions()
	{
		if (interactionsAll != null && !interactionsAll.isEmpty()) {
			return interactionsAll;
		}

		return interactionsFirstPage == null ? new ArrayList<LegislatorBillInteraction>() : interactionsFirstPage;
	}

	@JsonProperty
	public void setInteractions(List<LegislatorBillInteraction> list)
	{
		interactionsAll = copyInteractions(list);
	}

	@JsonIgnore
	public List<LegislatorBillInteraction> getInteractionsAll()
	{
		return interactionsAll;
	}

	public void setInteractionsAll(List<LegislatorBillInteraction> list)
	{
		interactionsAll = copyInteractions(list);
	}

	@JsonIgnore
	public List<LegislatorBillInteraction> getInteractionsFirstPage()
	{
		return interactionsFirstPage;
	}

	public void setInteractionsFirstPage(List<LegislatorBillInteraction> list)
	{
		interactionsFirstPage = copyInteractions(list);
	}

	public void clearInteractions() {
		interactionsAll.clear();
		interactionsFirstPage.clear();
	}
	
	public void addBillInteraction(LegislatorBillInteraction incoming)
	{
		interactionsAll.removeIf(existing -> incoming.supercedes(existing));
		
		if (!interactionsAll.contains(incoming)) {
			interactionsAll.add(incoming);
		}
	}

	private List<LegislatorBillInteraction> copyInteractions(List<LegislatorBillInteraction> list) {
		return list == null ? new ArrayList<LegislatorBillInteraction>() : new ArrayList<LegislatorBillInteraction>(list);
	}
	
	@JsonIgnore
	public Party getParty()
	{
		return this.terms.last().getParty();
	}
	
	public void setBirthday(LocalDate date) {
		if (date == null) return;
		
		this.birthday = date;
	}
	
//	@JsonIgnore
//	@DynamoDbIgnore
//	public LegislativeTerm getCurrentTerm()
//	{
//		if (this.terms == null || this.terms.size() == 0) return null;
//		
//		return this.terms.stream().filter(t -> t.getStartDate().isBefore(session.getEndDate()) && t.getEndDate().isAfter(session.getStartDate())).findFirst().orElse(null);
//	}
	
	public boolean isMemberOfSession(LegislativeSession session) {
		if (this.terms == null || this.terms.size() == 0 || session == null) return false;
		
		return this.terms.stream().anyMatch(t -> t.getStartDate().isBefore(session.getEndDate()) && t.getEndDate().isAfter(session.getStartDate()));
		
//		return this.terms.stream().anyMatch(t -> t.getStartDate().equals(session.getStartDate() && t.getEndDate().equals(session.getEndDate()));
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX, Persistable.OBJECT_BY_RATING_ABS_INDEX, Persistable.OBJECT_BY_LOCATION_INDEX, Persistable.OBJECT_BY_IMPACT_INDEX, Persistable.OBJECT_BY_IMPACT_ABS_INDEX}) public String getStorageBucket() { return super.getStorageBucket(); }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return birthday == null ? DEFAULT_BIRTHDAY : birthday; }
	@JsonIgnore public void setDate(LocalDate date) { this.setBirthday(date); }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public Integer getRating() { return interpretation == null ? null : interpretation.getRating(); }
	@JsonIgnore public void setRating(Integer rating) { }
	@JsonIgnore public Integer getRating(TrackedIssue issue) { return interpretation.getRating(issue); }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_ABS_INDEX }) public Integer getRatingAbs() { return interpretation == null || interpretation.getRating() == null ? null : Math.abs(interpretation.getRating()); }
	@JsonIgnore public void setRatingAbs(Integer rating) { }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_IMPACT_INDEX }) public Long getImpact() { return getImpact(TrackedIssue.OverallBenefitToSociety); }
	public void setImpact(Long impact) { impactMap.put(TrackedIssue.OverallBenefitToSociety, impact); }
	
	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_IMPACT_ABS_INDEX }) public Long getImpactAbs() { return Math.abs(getImpact()); }
	public void setImpactAbs(Long impact) { }
	
	// TODO : What could this be?
//	@DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_HOT_INDEX }) public int getHot() { return (int)(getImpactAbs() * Math.exp(-0.02 * ChronoUnit.DAYS.between(introducedDate, LocalDate.now()))); }
//	public void setHot(int hot) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_LOCATION_INDEX }) public String getLocation() { return this.terms.last().getState() + (this.terms.last().getDistrict() == null ? "" : "/" + this.terms.last().getDistrict() ); }
	@JsonIgnore public void setLocation(String location) { }
	
	public Long getImpact(TrackedIssue issue)
	{
		return impactMap.getOrDefault(issue, 0l);
	}

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
	@Column(name = "location_value")
	private String locationValue;

	@JsonIgnore
	@Column(name = "impact_value")
	private Long impactValue;

	@JsonIgnore
	@Column(name = "impact_abs_value")
	private Long impactAbsValue;
	
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class LegislatorName {
		
		protected String first;
		
		protected String last;
		
		protected String official_full;
		
	}
	
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	@EqualsAndHashCode
	public static class LegislativeTerm implements Comparable<LegislativeTerm> {
		
//		protected LegislativeSession session;
		
		protected LocalDate startDate;
		
		protected LocalDate endDate;
		
		@JsonDeserialize(using = LegiscanStateDeserializer.class)
		@JsonSerialize(using = LegiscanStateSerializer.class)
		@Getter(onMethod = @__({ @DynamoDbConvertedBy(LegiscanStateConverter.class) }))
		protected LegiscanState state;
		
		protected String district;
		
		protected Party party;
		
		protected LegislativeChamber chamber;

		@Override
		public int compareTo(LegislativeTerm o) {
			return this.getStartDate().compareTo(o.getStartDate());
		}
		
	}
	
	@Override
	public int compareTo(Legislator o) {
		return Integer.valueOf(Objects.requireNonNullElse(this.getRating(),-1)).compareTo(Objects.requireNonNullElse(o.getRating(),-1));
	}

	@Override
	protected void synchronizeJpaState()
	{
		storageBucketValue = getStorageBucket();
		dateValue = getDate() == null ? null : getDate().toString();
		ratingValue = getRating() == null ? null : Long.valueOf(getRating());
		ratingAbsValue = getRatingAbs() == null ? null : Long.valueOf(getRatingAbs());
		locationValue = terms == null || terms.isEmpty() ? null : getLocation();
		impactValue = getImpact();
		impactAbsValue = impactValue == null ? null : Math.abs(impactValue);
	}
	
}
