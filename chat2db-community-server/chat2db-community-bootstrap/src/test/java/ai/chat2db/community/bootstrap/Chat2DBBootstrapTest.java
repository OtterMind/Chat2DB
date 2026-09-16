package ai.chat2db.community.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Chat2DBBootstrapTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesStructuredRuntimeLayout() throws Exception {
        Path app = temporaryDirectory.resolve("app");
        Files.createDirectories(app.resolve("runtime/lib"));
        Files.createDirectories(app.resolve("runtime/dist"));
        Files.writeString(app.resolve("runtime/chat2db-community.jar"), "jar");
        Files.writeString(app.resolve("runtime/dist/index.html"), "html");
        Files.writeString(app.resolve("version.json"), "{}");
        LaunchConfiguration configuration = new LaunchConfiguration(
            "runtime/chat2db-community.jar", "example.Main", List.of("runtime/lib"),
            List.of("runtime/dist/index.html", "version.json"), Map.of("example", "value")
        );

        Chat2DBBootstrap.ValidatedLaunch result = Chat2DBBootstrap.validate(app, configuration);

        assertEquals(app.resolve("runtime/chat2db-community.jar"), result.mainJar());
        assertEquals(List.of(app.resolve("runtime/lib")), result.loaderPaths());
    }

    @Test
    void loadsJsonConfiguration() throws Exception {
        Path app = temporaryDirectory.resolve("app");
        Files.createDirectories(app.resolve("runtime"));
        LaunchConfiguration expected = new LaunchConfiguration(
            "runtime/app.jar", "example.Main", List.of("runtime/lib"), List.of(), Map.of()
        );
        new ObjectMapper().writeValue(app.resolve("runtime/launch.json").toFile(), expected);

        assertEquals(expected, Chat2DBBootstrap.loadConfiguration(app));
    }

    @Test
    void rejectsEscapingAndMissingPaths() throws Exception {
        Path app = temporaryDirectory.resolve("app");
        Files.createDirectories(app);

        assertThrows(IllegalArgumentException.class, () -> Chat2DBBootstrap.validate(
            app,
            new LaunchConfiguration("../outside.jar", "example.Main", List.of(), List.of(), Map.of())
        ));
        assertThrows(IllegalStateException.class, () -> Chat2DBBootstrap.validate(
            app,
            new LaunchConfiguration("runtime/missing.jar", "example.Main", List.of(), List.of(), Map.of())
        ));
    }
}
