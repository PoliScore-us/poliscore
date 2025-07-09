package us.poliscore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import us.poliscore.PoliscoreDataset.DatasetReference;
import us.poliscore.model.LegislativeNamespace;

public class PoliscoreUtil {
	
	public static File USC_DATA = new File("/Users/rrowlands/dev/projects/congress/data");
	
	public static File APP_DATA = new File(System.getProperty("user.home") + "/appdata/poliscore");
	{
		APP_DATA.mkdirs();
	}
	
//	public static DatasetReference DEPLOYMENT_DATASET = new DatasetReference(LegislativeNamespace.US_CONGRESS, 2026);
	public static DatasetReference DEPLOYMENT_DATASET = new DatasetReference(LegislativeNamespace.US_COLORADO, 2025);
	
	public static String DEPLOYMENT_SESSION_KEY;
	
	public static List<DatasetReference> SUPPORTED_DATASETS = Arrays.asList(DEPLOYMENT_DATASET, new DatasetReference(LegislativeNamespace.US_CONGRESS, 2024));
	
	public static List<File> allFilesWhere(File parent, Predicate<File> criteria)
	{
		List<File> all = new ArrayList<File>();
		
		if (!parent.isDirectory()) return all;
		
		for (File child : parent.listFiles())
		{
			if (child.isDirectory())
			{
				all.addAll(allFilesWhere(child, criteria));
			}
			else if (criteria.test(child))
			{
				all.add(child);
			}
		}
		
		return all;
	}
	
	public static ObjectMapper getObjectMapper() { return JsonMapper.builder().findAndAddModules().build(); }
	
}
