package us.poliscore.model.bill;

import java.time.LocalDate;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@RegisterForReflection
public class BillText extends SessionPersistable
{
	public static final String ID_CLASS_PREFIX = "BTX";
	
	public static String generateId(String billId) { return billId.replace(Bill.ID_CLASS_PREFIX, ID_CLASS_PREFIX); }
	
	@NonNull
	protected String billId;
	
	protected String xml;
	
	protected String text;
	
	protected LocalDate lastUpdated;
	
	public static BillText factoryFromText(String billId, String text, LocalDate lastUpdated) {
		val txt = new BillText();
		txt.billId = billId;
		txt.text = text;
		txt.lastUpdated = lastUpdated;
		txt.id = generateId(billId);
		return txt;
	}
	
	public static BillText factoryFromXml(String billId, String xml, LocalDate lastUpdated) {
		val txt = new BillText();
		txt.billId = billId;
		txt.xml = xml;
		txt.lastUpdated = lastUpdated;
		txt.id = generateId(billId);
		return txt;
	}
	
	@JsonIgnore
	@DynamoDbIgnore
	public String getDocument() {
		if (StringUtils.isBlank(text) && !StringUtils.isBlank(xml)) {
			return xml;
		}
		
		return text;
	}
	
	@Override @JsonIgnore @DynamoDbSecondaryPartitionKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public String getStorageBucket() { return super.getStorageBucket(); }
	@Override @JsonIgnore public void setStorageBucket(String prefix) { }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_DATE_INDEX }) public LocalDate getDate() { return lastUpdated; }
	@JsonIgnore public void setDate(LocalDate date) { lastUpdated = date; }
	
	@JsonIgnore @DynamoDbSecondarySortKey(indexNames = { Persistable.OBJECT_BY_RATING_INDEX }) public int getRating() { return 0; }
	@JsonIgnore public void setRating(int rating) { }
	
}
