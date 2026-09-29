package us.poliscore;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;

public class Environment {
	private static final String DEPLOY_PATH_PROPERTY = "poliscore.deployed.path";
	private static final String DEPLOY_PATH_ENVIRONMENT_VARIABLE = "POLISCORE_DEPLOYED_PATH";
	private static File deployPath = null;
	
	/**
	 * Calculates and returns the deployed path of the currently running application. The
	 * {@code poliscore.deployed.path} system property or {@code POLISCORE_DEPLOYED_PATH}
	 * environment variable can provide an explicit path. Otherwise, an exploded deployment
	 * resolves to its build/application directory and a packaged deployment resolves to the
	 * directory containing this class's jar.
	 * 
	 * @return An absolute file path of the deployed application path.
	 */
	public static File getDeployedPath()
	{
		if (deployPath != null)
		{
			return deployPath;
		}
		
		String configuredPath = firstNonBlank(
				System.getProperty(DEPLOY_PATH_PROPERTY),
				System.getenv(DEPLOY_PATH_ENVIRONMENT_VARIABLE));
		if (configuredPath != null)
		{
			deployPath = new File(configuredPath).getAbsoluteFile().toPath().normalize().toFile();
			return deployPath;
		}

		URL rootPath = Environment.class.getResource("/");
		URL codeSourceLocation = Environment.class.getProtectionDomain().getCodeSource() == null
				? null
				: Environment.class.getProtectionDomain().getCodeSource().getLocation();
		deployPath = resolveDeployedPath(rootPath, codeSourceLocation);
		return deployPath;
	}

	static File resolveDeployedPath(URL rootPath, URL codeSourceLocation)
	{
		// An exploded classpath root is the behavior the original implementation relied on.
		// A jar: root is an entry inside an archive, not a writable filesystem directory.
		if (isFileUrl(rootPath))
		{
			return deploymentDirectory(fileFromUrl(rootPath), false);
		}

		if (isFileUrl(codeSourceLocation))
		{
			return deploymentDirectory(fileFromUrl(codeSourceLocation), true);
		}

		// Native images and unusual class loaders may expose neither URL as a file.
		return new File(System.getProperty("user.dir")).getAbsoluteFile().toPath().normalize().toFile();
	}

	private static boolean isFileUrl(URL url)
	{
		return url != null && "file".equalsIgnoreCase(url.getProtocol());
	}

	private static File fileFromUrl(URL url)
	{
		try
		{
			return new File(URI.create(url.toExternalForm()));
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalStateException("Could not convert deployment URL to a filesystem path: " + url, e);
		}
	}

	private static File deploymentDirectory(File location, boolean codeSource)
	{
		Path path = location.getAbsoluteFile().toPath().normalize();
		String fileName = path.getFileName() == null ? "" : path.getFileName().toString();

		if (codeSource && isArchiveOrClassFile(fileName))
		{
			path = path.getParent();
		}

		if (path != null && path.getFileName() != null && "classes".equals(path.getFileName().toString()))
		{
			path = path.getParent();
			if (path != null && path.getFileName() != null && "WEB-INF".equals(path.getFileName().toString()))
			{
				path = path.getParent();
			}
		}

		if (path == null)
		{
			throw new IllegalStateException("Could not determine a deployment directory from " + location);
		}
		return path.toFile();
	}

	private static boolean isArchiveOrClassFile(String fileName)
	{
		String lowerCaseName = fileName.toLowerCase();
		return lowerCaseName.endsWith(".jar") || lowerCaseName.endsWith(".war") || lowerCaseName.endsWith(".class");
	}

	private static String firstNonBlank(String... values)
	{
		for (String value : values)
		{
			if (value != null && !value.isBlank())
			{
				return value.trim();
			}
		}
		return null;
	}
}
