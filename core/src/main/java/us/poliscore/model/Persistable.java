package us.poliscore.model;

import java.util.Objects;

import lombok.SneakyThrows;
import lombok.val;

public interface Persistable {
	
	public static final String OBJECT_BY_DATE_INDEX = "ObjectsByDate";
	
	public static final String OBJECT_BY_RATING_INDEX = "ObjectsByRating";
	
	public static final String OBJECT_BY_RATING_ABS_INDEX = "ObjectsByRatingAbs";
	
	public static final String OBJECT_BY_LOCATION_INDEX = "ObjectsByLocation";
	
	public static final String OBJECT_BY_IMPACT_INDEX = "ObjectsByImpact";
	
	public static final String OBJECT_BY_IMPACT_ABS_INDEX = "ObjectsByImpactAbs";
	
	public static final String OBJECT_BY_HOT_INDEX = "ObjectsByHot";
	
	public static final String OBJECT_BY_ISSUE_IMPACT_INDEX = "ObjectsByIssueImpact";
	
	public static final String OBJECT_BY_ISSUE_RATING_INDEX = "ObjectsByIssueRating";
	
	public String getId();
	public void setId(String id);
	
	public String getStorageBucket();
	public void setStorageBucket(String prefix);
	
	@SneakyThrows
	public static String getClassStorageBucket(Class<?> clazz, String sessionKey)
	{
		return getIdClassPrefix(clazz) + "/" + sessionKey;
	}
	
	@SneakyThrows
	public static String getClassStorageBucket(Class<?> clazz, LegislativeNamespace namespace, String sessionCode)
	{
		return getIdClassPrefix(clazz) + "/" + namespace.getNamespace() + "/" + sessionCode;
	}
	
	@SneakyThrows
	public static String getIdClassPrefix(Class<?> clazz)
	{
		return (String) clazz.getField("ID_CLASS_PREFIX").get(null);
	}
	
	public static void validate(Persistable p) {
		if (p.getId() == null)
			throw new UnsupportedOperationException("Persistable's id field is required.");
		
		val expectedPrefix = getIdClassPrefix(p.getClass());
		if (!Objects.equals(expectedPrefix, p.getId().split("/")[0]))
			throw new UnsupportedOperationException("Object's id class prefix does not match expected value.");
	}
}
