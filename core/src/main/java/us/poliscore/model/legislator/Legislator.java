package us.poliscore.model.legislator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

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
import us.poliscore.legiscan.view.LegiscanState;
import us.poliscore.legiscan.view.LegiscanState.LegiscanStateDeserializer;
import us.poliscore.legiscan.view.LegiscanState.LegiscanStateSerializer;
import us.poliscore.model.LegislativeChamber;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.dynamodb.DdbDataPage;
import us.poliscore.model.dynamodb.IssueStatsMapLongAttributeConverter;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.CompressedLegislatorBillInteractionListConverter;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.LegislatorBillInteractionSetConverterProvider;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.LegislatorLegislativeTermSortedSetConverter;
import us.poliscore.model.dynamodb.LegiscanStateConverter;

@Data
@DynamoDbBean
@NoArgsConstructor
@RegisterForReflection
public class Legislator implements Persistable, Comparable<Legislator> {
	
	public static final String ID_CLASS_PREFIX = "LEG";
	
	/**
	 * An optional grouping mechanism, beyond the ID_CLASS_PREFIX concept, which allows you to group objects of the same class in different
	 * "storage buckets". Really only used in DynamoDb at the moment, and is used for querying on the object indexes with objects that exist
	 * in different congressional sessions.
	 */
	public static String getClassStorageBucket(LegislativeNamespace namespace, String sessionKey)
	{
		return ID_CLASS_PREFIX + "/" + namespace.getNamespace() + "/" + sessionKey;
	}
	
	@NonNull
	protected LegislatorName name;
	
	protected String id;
	
	// Senate Id (only used in congress) : https://github.com/usgpo/bill-status/issues/241
	protected String lisId;
	
	protected LegislatorInterpretation interpretation;
	
	@NonNull
	protected LocalDate birthday;
	
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(IssueStatsMapLongAttributeConverter.class) }))
	public Map<TrackedIssue, Long> impactMap = new HashMap<TrackedIssue, Long>();
	
	@NonNull
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(LegislatorLegislativeTermSortedSetConverter.class) }))
	protected LegislatorLegislativeTermSortedSet terms = new LegislatorLegislativeTermSortedSet();
	
	private LegislatorBillInteractionList interactionsPrivate1 = new LegislatorBillInteractionList();
	@DdbDataPage
	@DynamoDbConvertedBy(CompressedLegislatorBillInteractionListConverter.class)
	@JsonIgnore
	public LegislatorBillInteractionList getInteractionsPrivate1() {
		return interactionsPrivate1;
	}
	private LegislatorBillInteractionList interactionsPrivate2 = new LegislatorBillInteractionList();
	@DdbDataPage("2")
	@DynamoDbConvertedBy(CompressedLegislatorBillInteractionListConverter.class)
	@JsonIgnore
	public LegislatorBillInteractionList getInteractionsPrivate2() {
		return interactionsPrivate2;
	}
	
	@JsonProperty
	public LegislatorBillInteractionList getInteractions()
	{
		var result = new LegislatorBillInteractionList();
		result.addAll(interactionsPrivate1);
		result.addAll(interactionsPrivate2);
		return result;
	}
	
	@JsonProperty
	public void setInteractions(LegislatorBillInteractionList list)
	{
		interactionsPrivate1 = new LegislatorBillInteractionList();
		interactionsPrivate1.addAll(list.subList(0, Math.min(1000, list.size())));
		
		interactionsPrivate2 = new LegislatorBillInteractionList();
		if (list.size() > 1000)
			interactionsPrivate2.addAll(list.subList(1000, list.size()));
	}
	
	public void clearInteractions() {
		interactionsPrivate1.clear();
		interactionsPrivate2.clear();
	}
	
	@DynamoDbPartitionKey
	public String getId()
	{
		return id;
	}
	
	public void setId(String id) { this.id = id; }
	
	@JsonIgnore
	@DynamoDbIgnore
	public String getCode() {
		return Arrays.asList(this.id.split("/")).getLast();
	}
	
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
	
	public void addBillInteraction(LegislatorBillInteraction incoming)
	{
		var interactions = interactionsPrivate1;
		if (interactions.size() >= 1000)
		{
			interactions = interactionsPrivate2;
		}
		
		interactions.removeIf(existing -> incoming.supercedes(existing));
		
		if (!interactions.contains(incoming)) {
			interactions.add(incoming);
		}
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public Party getParty()
	{
		return this.terms.last().getParty();
	}
	
	public void setBirthday(LocalDate date) {
		if (date == null)
			date = LocalDate.of(1970, 1, 1);
		
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
	
	public static String generateId(LegislativeNamespace ns, LegislativeSession session, String legislatorCode)
	{
		return ID_CLASS_PREFIX + "/" + ns.getNamespace() + "/" + session.getCode() + "/" + legislatorCode;
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX, Persistable.OBJECT_BY_RATING_ABS_INDEX, Persistable.OBJECT_BY_LOCATION_INDEX, Persistable.OBJECT_BY_IMPACT_INDEX, Persistable.OBJECT_BY_IMPACT_ABS_INDEX}) public String getStorageBucket() {
		return this.getId().substring(0, StringUtils.ordinalIndexOf(getId(), "/", 4));
	}
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return birthday; }
	@JsonIgnore public void setDate(LocalDate date) { this.setBirthday(date); }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return interpretation == null ? -1 : interpretation.getRating(); }
	@JsonIgnore public void setRating(int rating) { }
	@JsonIgnore public int getRating(TrackedIssue issue) { return interpretation.getRating(issue); }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_ABS_INDEX }) public int getRatingAbs() { return interpretation == null ? -1 : Math.abs(interpretation.getRating()); }
	@JsonIgnore public void setRatingAbs(int rating) { }
	
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
	
	@Data
	@DynamoDbBean
	@NoArgsConstructor
	@AllArgsConstructor
	public static class LegislatorName {
		
		protected String first;
		
		protected String last;
		
		protected String official_full;
		
	}
	
	@Data
	@DynamoDbBean
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
	
	@DynamoDbBean(converterProviders = LegislatorBillInteractionSetConverterProvider.class)
	public static class LegislatorBillInteractionList extends ArrayList<LegislatorBillInteraction> {}
	
	@DynamoDbBean(converterProviders = LegislatorBillInteractionSetConverterProvider.class)
	public static class LegislatorBillInteractionSet extends TreeSet<LegislatorBillInteraction> {}
	
	@DynamoDbBean
	public static class LegislatorLegislativeTermSortedSet extends TreeSet<LegislativeTerm> {}

	@Override
	public int compareTo(Legislator o) {
		return Integer.valueOf(this.getRating()).compareTo(o.getRating());
	}
	
}
