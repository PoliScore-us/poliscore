package us.poliscore.model.session;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.IssueStats;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.Persistable;
import us.poliscore.model.StructuralStats;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill.BillSponsor;
import us.poliscore.model.bill.BillStatus;
import us.poliscore.model.dynamodb.DdbDataPage;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.AIInterpretationMetadataConverter;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.CompressedPartyStatsConverter;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.util.ParsingUtil;

@Data
@RegisterForReflection
@NoArgsConstructor
@Entity
@Table(name = "session_interpretation", indexes = {
		@Index(name = "session_interpretation_storage_bucket_idx", columnList = "storage_bucket"),
		@Index(name = "session_interpretation_date_idx", columnList = "storage_bucket, date_value, id")
})
@Access(AccessType.FIELD)
public class SessionInterpretation implements Persistable {
	
	public static final String ID_CLASS_PREFIX = "SIT";
	
	@Id
	@Column(name = "id", nullable = false)
	protected String id;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected LegislativeSession session;
	
//	@Getter(onMethod = @__({ @DynamoDbConvertedBy(PartyStatsMapAttributeConverter.class) }))
//	protected Map<Party, PartyStats> partyStats = new HashMap<Party, PartyStats>();
	
	@Getter(onMethod = @__({ @DdbDataPage("1"), @DynamoDbConvertedBy(CompressedPartyStatsConverter.class) }))
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected PartyInterpretation democrat;
	
	@Getter(onMethod = @__({ @DdbDataPage("2"), @DynamoDbConvertedBy(CompressedPartyStatsConverter.class) }))
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected PartyInterpretation republican;
	
	@Getter(onMethod = @__({ @DdbDataPage("3"), @DynamoDbConvertedBy(CompressedPartyStatsConverter.class) }))
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected PartyInterpretation independent;
	
	@NonNull
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(AIInterpretationMetadataConverter.class)}))
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	protected AIInterpretationMetadata metadata;

	@JsonIgnore
	@Column(name = "storage_bucket", nullable = false)
	protected String storageBucketValue = ID_CLASS_PREFIX;

	@JsonIgnore
	@Column(name = "date_value")
	protected String dateValue;
	
	public static String generateId(LegislativeNamespace namespace, String sessionCode)
	{
		return ID_CLASS_PREFIX + "/" + namespace.getNamespace() + "/" + sessionCode;
	}
	
	@DynamoDbPartitionKey
	@Override
	public String getId()
	{
		if (StringUtils.isBlank(id) && session != null) {
			id = generateId(session.getNamespace(), session.getCode());
		}
		return id;
	}
	
	@Override
	public void setId(String id) { this.id = id; }

	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX }) public String getStorageBucket() { return ID_CLASS_PREFIX; }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }

	@Transient
	@JsonIgnore
	public LocalDate getDate() {
		return metadata == null ? null : metadata.getDate();
	}

	public void setDate(LocalDate date) { }

	@Data
	@NoArgsConstructor
	@RegisterForReflection
	@AllArgsConstructor
	public static class PartyInterpretation {
		protected Party party;
		
		protected IssueStats stats;
		
		@NonNull
		protected String longExplain;
		
		protected String shortExplain;
		
		protected String casualExplain;
		
		protected String reasoning;
		
		protected String references;
		
		protected StructuralStats structuralStats = new StructuralStats();
		
		@NonNull
		protected PartyBillSet mostImportantBills = new PartyBillSet();
		
		@NonNull
		protected PartyBillSet leastImportantBills = new PartyBillSet();
		
		@NonNull
		protected PartyBillSet bestBills = new PartyBillSet();
		
		@NonNull
		protected PartyBillSet worstBills = new PartyBillSet();
		
		@NonNull
		protected PartyLegislatorSet bestLegislators = new PartyLegislatorSet();
		
		@NonNull
		protected PartyLegislatorSet worstLegislators = new PartyLegislatorSet();
		
		public void validate()
		{
			ParsingUtil.strValidate(getLongExplain(), "long explain (" + party.getName() + ")", 15_000);
			ParsingUtil.strValidate(getCasualExplain(), "casual report (" + party.getName() + ")", 6000);
			ParsingUtil.strValidate(getShortExplain(), "short explain (" + party.getName() + ")", 3000);
			
			if (stats == null || !stats.hasStat(TrackedIssue.OverallBenefitToSociety)) {
				throw new IllegalStateException("Unable to parse valid issue stats for " + party.getName());
			}
		}
	}
	
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@RegisterForReflection
	@EqualsAndHashCode
	public static class PartyBillInteraction implements Comparable<PartyBillInteraction>
	{
		// Lots of existing assumptions that this is the bill id.
		@NonNull
		@JsonAlias("billId")
		protected String id;
		
		@NonNull
		@EqualsAndHashCode.Exclude
		protected String name;
		
		@NonNull
		@EqualsAndHashCode.Exclude
		protected BillStatus billStatus;
		
		@NonNull
		@EqualsAndHashCode.Exclude
		protected String type;
		
		@EqualsAndHashCode.Exclude
		protected LocalDate introducedDate;
		
		@EqualsAndHashCode.Exclude
		protected BillSponsor sponsor;
		
		@EqualsAndHashCode.Exclude
		protected List<BillSponsor> cosponsors;
		
		@EqualsAndHashCode.Exclude
		protected Integer rating;
		
		@EqualsAndHashCode.Exclude
		protected Integer impact;
		
		@EqualsAndHashCode.Exclude
		@NonNull
		protected String letterGrade;
		
		@EqualsAndHashCode.Exclude
		protected String shortExplain;
		
		@JsonIgnore
		public String getShortExplainForInterp() {
			return "- " + " \"" + name + "\" (Grade: " + letterGrade + ") (" + cosponsors.size() + " cosponsors" + getCosponsorPartyDescription() + ") (" + billStatus.getDescription() + ") (" + id + "): " + shortExplain;
		}
		
		protected String getCosponsorPartyDescription() {
			if (cosponsors.size() == 0) return "";
			
			float total = (float) cosponsors.size();
			float dem = (float) cosponsors.stream().filter(cs -> cs.getParty().equals(Party.DEMOCRAT)).count();
			float repub = (float) cosponsors.stream().filter(cs -> cs.getParty().equals(Party.REPUBLICAN)).count();
			
			if (dem > repub) {
				return ", " + Math.round((dem / total)*100f) + " percent democrat";
			} else {
				return ", " + Math.round((repub / total)*100f) + " percent republican";
			}
		}
		
		@JsonIgnore
		public Integer getImpact() {
			return impact;
		}

		@Override
		public int compareTo(PartyBillInteraction o) {
			return getImpact().compareTo(o.getImpact());
		}
	}
	
	/**
	 * TreeSet was decided against for these methods because TreeSet doesn't allow duplicates on the 'compareTo' method (which is sorted on rating for legislators)
	 */
	
	@NoArgsConstructor
	public static class PartyBillSet extends ArrayList<PartyBillInteraction> {

		public PartyBillSet(Collection c) {
			super(c);
		}
	}
	
	@NoArgsConstructor
	public static class PartyLegislatorSet extends ArrayList<Legislator> {

		public PartyLegislatorSet(Collection c) {
			super(c);
		}
	}

	public boolean isComplete(boolean hasIndependent) {
		if (hasIndependent)
			return democrat != null && democrat.longExplain != null && republican != null && republican.longExplain != null && independent != null && independent.longExplain != null;
		else
			return democrat != null && democrat.longExplain != null && republican != null && republican.longExplain != null;
	}

	public PartyInterpretation getPartyInterp(Party party) {
		if (Party.REPUBLICAN.equals(party)) {
			return republican;
		} else if (Party.INDEPENDENT.equals(party)) {
			return independent;
		} else if (Party.DEMOCRAT.equals(party)) {
			return democrat;
		} else {
			throw new UnsupportedOperationException(party != null ? party.getName() : "null");
		}
	}

	@PrePersist
	@PreUpdate
	protected void synchronizeJpaState()
	{
		id = getId();
		storageBucketValue = getStorageBucket();
		dateValue = getDate() == null ? null : getDate().toString();
	}
}
