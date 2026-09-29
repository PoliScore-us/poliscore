package us.poliscore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentTest {

	@TempDir
	Path tempDir;

	@Test
	void resolvesExplodedClassesDirectoryToBuildDirectory() throws Exception {
		Path classes = tempDir.resolve("project with spaces/target/classes");

		assertEquals(tempDir.resolve("project with spaces/target").toFile(),
				Environment.resolveDeployedPath(classes.toUri().toURL(), null));
	}

	@Test
	void resolvesExplodedWarClassesDirectoryToApplicationDirectory() throws Exception {
		Path classes = tempDir.resolve("server/webapps/poliscore/WEB-INF/classes");

		assertEquals(tempDir.resolve("server/webapps/poliscore").toFile(),
				Environment.resolveDeployedPath(classes.toUri().toURL(), null));
	}

	@Test
	void ignoresJarResourceRootAndUsesCodeSourceDirectory() throws Exception {
		Path dependencyJar = tempDir.resolve("deployments/lib/boot/jboss-logmanager.jar");
		URL versionedJarRoot = new URI("jar:" + dependencyJar.toUri() + "!/META-INF/versions/17/").toURL();
		Path applicationJar = tempDir.resolve("deployments/lib/main/us.poliscore.core.jar");

		assertEquals(applicationJar.getParent().toFile(),
				Environment.resolveDeployedPath(versionedJarRoot, applicationJar.toUri().toURL()));
	}
}
