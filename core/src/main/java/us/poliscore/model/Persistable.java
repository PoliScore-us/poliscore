package us.poliscore.model;

import lombok.SneakyThrows;

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
	public static String getClassStorageBucket(Class<?> clazz, LegislativeNamespace namespace, String sessionCode)
	{
//		try { return (String) clazz.getMethod("getClassStorageBucket", LegislativeNamespace.class, String.class).invoke(null, namespace, sessionCode); } catch (Throwable t) { }
//		try { return (String) clazz.getMethod("getClassStorageBucket").invoke(null); } catch (Throwable t) { }
//		
//		return (String) clazz.getField("ID_CLASS_PREFIX").get(null);
		
		String idClassPrefix = (String) clazz.getField("ID_CLASS_PREFIX").get(null);
		
		return idClassPrefix + "/" + namespace.getNamespace() + "/" + sessionCode;
	}
	
	@SneakyThrows
	public static String getIdClassPrefix(Class<?> clazz)
	{
		return (String) clazz.getField("ID_CLASS_PREFIX").get(null);
	}
}
