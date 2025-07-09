package us.poliscore.model;

import io.quarkus.logging.Log;
import lombok.SneakyThrows;
import us.poliscore.PoliscoreUtil;

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
		try { return (String) clazz.getMethod("getClassStorageBucket", String.class).invoke(null, sessionKey); } catch (Throwable t) { t.printStackTrace(); }
		try { return (String) clazz.getMethod("getClassStorageBucket").invoke(null); } catch (Throwable t) { }
		
		return (String) clazz.getField("ID_CLASS_PREFIX").get(null);
	}
	
	@SneakyThrows
	public static String getIdClassPrefix(Class<?> clazz)
	{
		return (String) clazz.getField("ID_CLASS_PREFIX").get(null);
	}
}
