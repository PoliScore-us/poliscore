package us.poliscore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Arc;
import us.poliscore.service.SessionInfoService;
import us.poliscore.service.PoliscoreCacheConfig;

public class PoliscoreUtil {
	private static String defaultCachePath() {
		return System.getProperty("user.home") + "/appdata/poliscore";
	}

	private static String configuredCachePath() {
		try {
			var handle = Arc.container().instance(PoliscoreCacheConfig.class);
			if (handle.isAvailable()) {
				return handle.get().cachePath();
			}
		} catch (Throwable ignored) {
			// Arc might not be running yet (for example in plain unit tests).
		}

		return defaultCachePath();
	}

	private static File resolveAppData() {
		String configuredPath = configuredCachePath();
		File appData = new File(configuredPath).getAbsoluteFile();
		appData.mkdirs();
		return appData;
	}

	public static File appData() {
		return resolveAppData();
	}

	public static File cacheFile(String childPath) {
		File file = new File(appData(), childPath);
		File parent = file.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		return file;
	}

	public static File cacheDir(String childPath) {
		File directory = new File(appData(), childPath);
		directory.mkdirs();
		return directory;
	}
	
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
	
	public static ObjectMapper getObjectMapper() { return SessionInfoService.mapper(); }
	
}
