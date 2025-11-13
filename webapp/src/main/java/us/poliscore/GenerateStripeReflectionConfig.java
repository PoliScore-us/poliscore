package us.poliscore;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class GenerateStripeReflectionConfig {
    public static void main(String[] args) throws IOException {
        final String root = "com/stripe/"; // include EVERYTHING under com.stripe
        final Set<String> classes = new TreeSet<>();

        String cp = System.getProperty("java.class.path");
        String sep = System.getProperty("path.separator");
        for (String element : cp.split(sep)) {
            if (element.isBlank()) continue;
            Path p = Paths.get(element);
            if (Files.isDirectory(p)) {
                // scan directory classpath entries (e.g., target/classes)
                if (Files.exists(p)) {
                    try (var stream = Files.walk(p)) {
                        stream.filter(f -> f.toString().endsWith(".class"))
                              .forEach(f -> {
                                  String rel = p.relativize(f).toString().replace('\\','/');
                                  if (!rel.startsWith(root)) return;
                                  if (skip(rel)) return;
                                  classes.add(toClassName(rel));
                              });
                    }
                }
            } else if (element.endsWith(".jar")) {
                // scan jars
                try (JarFile jar = new JarFile(element)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry je = entries.nextElement();
                        String name = je.getName();
                        if (!name.startsWith(root)) continue;
                        if (!name.endsWith(".class")) continue;
                        if (skip(name)) continue;
                        classes.add(toClassName(name));
                    }
                } catch (IOException e) {
                    System.err.println("Skipping unreadable JAR: " + element + " (" + e.getMessage() + ")");
                }
            }
        }

        // Emit JSON
        PrintWriter out = new PrintWriter(System.out);
        out.println("[");
        boolean first = true;
        for (String cls : classes) {
            if (!first) out.println(",");
            first = false;
            out.print("  { \"name\": \"" + cls + "\", " +
                      "\"allDeclaredConstructors\": true, " +
                      "\"allDeclaredFields\": true, " +
                      "\"allDeclaredMethods\": true, " +
                      "\"unsafeAllocated\": true }");
        }
        out.println();
        out.println("]");
        out.flush();

        System.err.println("Found " + classes.size() + " com.stripe classes.");
    }

    private static boolean skip(String pathLike) {
	  if (!pathLike.endsWith(".class")) return true;
	  if (pathLike.contains("$Lambda$")) return true;
	  if (pathLike.endsWith("package-info.class")) return true;
	  if (pathLike.endsWith("module-info.class")) return true;
	  if (pathLike.startsWith("com/stripe/examples/")) return true;  // <-- exclude
	  if (pathLike.startsWith("com/stripe/testing/"))  return true;  // <-- optional exclude
	  return false;
	}

    private static String toClassName(String pathLike) {
        return pathLike.substring(0, pathLike.length() - ".class".length()).replace('/', '.');
    }
}
