package ai.chat2db.community.bootstrap;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.loader.launch.PropertiesLauncher;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

class SpringBootLaunchTest {
    @TempDir Path temporary;

    @Test
    void launchesBootClassesAndResourcesThroughAnIsolatedRuntimeLoader() throws Exception {
        Path source = temporary.resolve("FixtureMain.java");
        Files.writeString(source, """
                public class FixtureMain {
                    public static void main(String[] args) throws Exception {
                        // Embedded servers must still be able to install their own protocol factory.
                        java.net.URL.setURLStreamHandlerFactory(protocol -> null);
                        try (var resource = FixtureMain.class.getResourceAsStream("/fixture.txt")) {
                            System.out.println(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, source.toString()));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Start-Class", "FixtureMain");
        Path application = temporary.resolve("application.jar");
        try (JarFile loader = new JarFile(location(PropertiesLauncher.class).toFile());
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(application), manifest)) {
            var entries = loader.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("org/")) continue;
                output.putNextEntry(new JarEntry(entry.getName()));
                try (var input = loader.getInputStream(entry)) { input.transferTo(output); }
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry("BOOT-INF/classes/"));
            output.closeEntry();
            output.putNextEntry(new JarEntry("BOOT-INF/classes/FixtureMain.class"));
            Files.copy(temporary.resolve("FixtureMain.class"), output);
            output.closeEntry();
            output.putNextEntry(new JarEntry("BOOT-INF/classes/fixture.txt"));
            output.write("BOOTSTRAP_LAUNCHED".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        // Boot's loader must not be visible to the child JVM's system classloader.
        String classpath = String.join(File.pathSeparator, List.of(location(Chat2DBBootstrap.class).toString(),
                location(LaunchProbe.class).toString(), location(ObjectMapper.class).toString(),
                location(JsonFactory.class).toString(), location(JsonProperty.class).toString()));
        Path log = temporary.resolve("launch.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", classpath, LaunchProbe.class.getName(), application.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Bootstrap did not finish");
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("BOOTSTRAP_LAUNCHED"));
        } finally { process.destroyForcibly(); }
    }

    private static Path location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    public static class LaunchProbe {
        public static void main(String[] args) throws Exception {
            Path application = Path.of(args[0]);
            Chat2DBBootstrap.launch(new Chat2DBBootstrap.ValidatedLaunch(application.getParent(), application,
                    "org.springframework.boot.loader.launch.PropertiesLauncher", List.of(), Map.of()), new String[0]);
        }
    }
}
