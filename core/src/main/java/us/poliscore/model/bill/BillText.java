package us.poliscore.model.bill;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

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
	private static final Pattern DUBLIN_CORE_DATE_PATTERN = Pattern.compile("<(?:\\w+:)?date>([^<]+)</(?:\\w+:)?date>");
	private static final Pattern ACTION_DATE_PATTERN = Pattern.compile("<action-date[^>]*date=\"(\\d{8})\"");
	private static final Pattern ATTESTATION_DATE_PATTERN = Pattern.compile("<attestation-date[^>]*date=\"(\\d{8})\"");
	
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

	protected String version;
	
	protected BillTextFormat format;
	
	protected Integer legiscanId;
	
	public static BillText factory(String billId, Integer legiscanId, String text, LocalDate lastUpdated, BillTextFormat format) {
		return factory(billId, legiscanId, text, lastUpdated, (String)null, format);
	}
	
	public static BillText factory(String billId, Integer legiscanId, String text, LocalDate lastUpdated, BillTextPublishVersion version, BillTextFormat format) {
		return factory(billId, legiscanId, text, lastUpdated, version != null ? version.name() : null, format);
	}
	
	public static BillText factory(String billId, Integer legiscanId, String text, LocalDate lastUpdated, String version, BillTextFormat format) {
		val txt = new BillText();
		txt.billId = billId;
		txt.legiscanId = legiscanId;
		txt.text = text;
		txt.setLastUpdate(lastUpdated != null ? lastUpdated.atStartOfDay() : null);
		txt.version = version;
		txt.format = format;
		txt.id = generateId(billId, version);
		return txt;
	}
	
	@JsonIgnore
    protected transient boolean loadedLegacyXml;

    public void setText(String text) {
        this.text = text;
    }

    @JsonSetter("text")
    public void jsonSetText(String text) {
        if (StringUtils.isBlank(text) && loadedLegacyXml && StringUtils.isNotBlank(this.text)) {
            return;
        }

        this.text = text;
    }

    @Deprecated
    @JsonSetter("xml")
    public void jsonSetXml(String xml) {
        if (StringUtils.isBlank(xml)) {
            return;
        }

        this.text = xml;
        this.loadedLegacyXml = true;

        if (format == null) {
            this.format = BillTextFormat.XML;
        }
    }
	
	@JsonIgnore
	@DynamoDbIgnore
	public String getDocument() {
		return text;
	}

	@JsonIgnore
	@DynamoDbIgnore
	public BillText metadataOnly() {
		val copy = new BillText();
		copy.setId(getId());
		copy.setBillId(getBillId());
		copy.setVersion(getVersion());
		copy.setFormat(getFormat());
		copy.setLegiscanId(getLegiscanId());
		copy.setLastUpdate(getLastUpdate());
		copy.setStorageBucket(getStorageBucket());
		return copy;
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

	@JsonIgnore
	@DynamoDbIgnore
	public LocalDate getLastUpdated() {
		return getLastUpdate() != null ? getLastUpdate().toLocalDate() : null;
	}

	@JsonSetter("lastUpdated")
	public void setLastUpdated(LocalDate lastUpdated) {
		super.setLastUpdate(lastUpdated != null ? lastUpdated.atStartOfDay() : null);
	}

	@Override
	public LocalDateTime getLastUpdate() {
		LocalDateTime lastUpdate = super.getLastUpdate();
		if (lastUpdate != null) {
			return lastUpdate;
		}
		
		LocalDate derivedDate = deriveDateFromDocument();
		if (derivedDate == null) {
			return null;
		}
		
		LocalDateTime derivedDateTime = derivedDate.atStartOfDay();
		super.setLastUpdate(derivedDateTime);
		return derivedDateTime;
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public String getStorageBucket() { return super.getStorageBucket(); }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return getLastUpdated(); }
	@JsonIgnore public void setDate(LocalDate date) { super.setLastUpdate(date != null ? date.atStartOfDay() : null); }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return 0; }
	@JsonIgnore public void setRating(int rating) { }

	@JsonIgnore
	@DynamoDbIgnore
	protected LocalDate deriveDateFromDocument() {
		if (StringUtils.isBlank(text) || !BillTextFormat.XML.equals(getEffectiveFormat())) {
			return null;
		}
		
		val dublinCoreMatch = DUBLIN_CORE_DATE_PATTERN.matcher(text);
		if (dublinCoreMatch.find()) {
			return LocalDate.parse(dublinCoreMatch.group(1).trim(), DateTimeFormatter.ISO_LOCAL_DATE);
		}
		
		val actionDateMatch = ACTION_DATE_PATTERN.matcher(text);
		if (actionDateMatch.find()) {
			return LocalDate.parse(actionDateMatch.group(1), DateTimeFormatter.BASIC_ISO_DATE);
		}
		
		val attestationDateMatch = ATTESTATION_DATE_PATTERN.matcher(text);
		if (attestationDateMatch.find()) {
			return LocalDate.parse(attestationDateMatch.group(1), DateTimeFormatter.BASIC_ISO_DATE);
		}
		
		return null;
	}
	
}
