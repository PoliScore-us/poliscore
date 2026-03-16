package us.poliscore.model.bill;

import java.time.LocalDate;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.val;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import us.poliscore.model.Persistable;
import us.poliscore.model.SessionPersistable;

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@RegisterForReflection
public class BillText extends SessionPersistable
{
	public static final String ID_CLASS_PREFIX = "BTX";
	
	@Deprecated // TODO : Remove this after patching
	public static String generateId(String billId) { return billId.replace(Bill.ID_CLASS_PREFIX, ID_CLASS_PREFIX); }
	
	public static String generateId(String billId, BillTextPublishVersion version) {
		return generateId(billId, version != null ? version.name() : null);
	}
	
	public static String generateId(String billId, String version) {
		if (StringUtils.isBlank(version)) {
			return generateId(billId);
		}
		
		return generateId(billId) + "/" + version;
	}
	
	@NonNull
	protected String billId;
	
	protected String text;
	
	protected LocalDate lastUpdated;
	
	protected String version;
	
	protected BillTextFormat format;
	
	public static BillText factory(String billId, String text, LocalDate lastUpdated, BillTextFormat format) {
		return factory(billId, text, lastUpdated, (String)null, format);
	}
	
	public static BillText factory(String billId, String text, LocalDate lastUpdated, BillTextPublishVersion version, BillTextFormat format) {
		return factory(billId, text, lastUpdated, version != null ? version.name() : null, format);
	}
	
	public static BillText factory(String billId, String text, LocalDate lastUpdated, String version, BillTextFormat format) {
		val txt = new BillText();
		txt.billId = billId;
		txt.text = text;
		txt.lastUpdated = lastUpdated;
		txt.version = version;
		txt.format = format;
		txt.id = generateId(billId, version);
		return txt;
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public String getDocument() {
		return text;
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public BillTextFormat getEffectiveFormat() {
		if (format != null) {
			return format;
		}
		
		return StringUtils.startsWithIgnoreCase(StringUtils.stripStart(text, null), "<") ? BillTextFormat.XML : BillTextFormat.TEXT;
	}
	
	@Deprecated
	@JsonIgnore
	@DynamoDbIgnore
	public String getXml() {
		return BillTextFormat.XML.equals(getEffectiveFormat()) ? text : null;
	}
	
	@Deprecated
	@JsonSetter("xml")
	public void setXml(String xml) {
		if (StringUtils.isBlank(xml)) {
			return;
		}
		
		this.text = xml;
		if (format == null) {
			this.format = BillTextFormat.XML;
		}
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public String getStorageBucket() { return super.getStorageBucket(); }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return lastUpdated; }
	@JsonIgnore public void setDate(LocalDate date) { lastUpdated = date; }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return 0; }
	@JsonIgnore public void setRating(int rating) { }
	
}
