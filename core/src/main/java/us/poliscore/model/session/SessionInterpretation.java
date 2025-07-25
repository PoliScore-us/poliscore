package us.poliscore.model.session;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.IssueStats;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.Party;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill.BillSponsor;
import us.poliscore.model.bill.BillStatus;
import us.poliscore.model.dynamodb.DdbDataPage;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.AIInterpretationMetadataConverter;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.LegislatorBillInteractionSetConverterProvider;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.dynamodb.JacksonAttributeConverter.CompressedPartyStatsConverter;

@Data
@DynamoDbBean
@RegisterForReflection
@NoArgsConstructor
public class SessionInterpretation implements Persistable {
	
	public static final String ID_CLASS_PREFIX = "SIT";
	
	protected LegislativeSession session;
	
//	@Getter(onMethod = @__({ @DynamoDbConvertedBy(PartyStatsMapAttributeConverter.class) }))
//	protected Map<Party, PartyStats> partyStats = new HashMap<Party, PartyStats>();
	
	@Getter(onMethod = @__({ @DdbDataPage("1"), @DynamoDbConvertedBy(CompressedPartyStatsConverter.class) }))
	protected PartyInterpretation democrat;
	
	@Getter(onMethod = @__({ @DdbDataPage("2"), @DynamoDbConvertedBy(CompressedPartyStatsConverter.class) }))
	protected PartyInterpretation republican;
	
	@Getter(onMethod = @__({ @DdbDataPage("3"), @DynamoDbConvertedBy(CompressedPartyStatsConverter.class) }))
	protected PartyInterpretation independent;
	
	@NonNull
	@Getter(onMethod = @__({ @DynamoDbConvertedBy(AIInterpretationMetadataConverter.class)}))
	protected AIInterpretationMetadata metadata;
	
	public static String generateId(LegislativeNamespace namespace, String sessionCode)
	{
		return ID_CLASS_PREFIX + "/" + namespace.getNamespace() + "/" + sessionCode;
	}
	
	@DynamoDbPartitionKey
	public String getId()
	{
		return generateId(session.getNamespace(), session.getCode());
	}
	
	public void setId(String id) { }

	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX, Persistable.OBJECT_BY_RATING_INDEX }) public String getStorageBucket() { return ID_CLASS_PREFIX; }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }

	@Data
	@DynamoDbBean
	@NoArgsConstructor
	@RegisterForReflection
	@AllArgsConstructor
	public static class PartyInterpretation {
		protected Party party;
		
		protected IssueStats stats;
		
		@NonNull
		protected String longExplain;
		
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
	}
	
	@Data
	@DynamoDbBean
	@NoArgsConstructor
	@AllArgsConstructor
	@RegisterForReflection
	@EqualsAndHashCode
	public static class PartyBillInteraction implements Comparable<PartyBillInteraction>
	{
		@NonNull
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
		protected String shortExplain;
		
		@DynamoDbIgnore
		@JsonIgnore
		public String getShortExplainForInterp() {
			return "- " + this.name + " (" + billStatus.getDescription() + ") (" + (cosponsors.size()) + " cosponsors" + getCosponsorPartyDescription() + ")" + ": " + this.shortExplain;
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
		
		@DynamoDbIgnore
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
	
	@DynamoDbBean(converterProviders = LegislatorBillInteractionSetConverterProvider.class)
	@NoArgsConstructor
	public static class PartyBillSet extends ArrayList<PartyBillInteraction> {

		public PartyBillSet(Collection c) {
			super(c);
		}
	}
	
	@DynamoDbBean(converterProviders = LegislatorBillInteractionSetConverterProvider.class)
	@NoArgsConstructor
	public static class PartyLegislatorSet extends ArrayList<Legislator> {

		public PartyLegislatorSet(Collection c) {
			super(c);
		}
	}
}
