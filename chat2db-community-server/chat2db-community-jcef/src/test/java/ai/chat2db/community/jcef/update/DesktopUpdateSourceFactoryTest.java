package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopUpdateSourceFactoryTest {

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void tearDown() {
        System.clearProperty(DesktopUpdateSourceFactory.DEVELOPMENT_DIRECTORY_PROPERTY);
        System.clearProperty("chat2db.runtime.mode");
        System.clearProperty("chat2db.mode");
        System.clearProperty("spring.profiles.active");
    }

    @Test
    void selectsLocalSourceOnlyForExplicitCommunityDesktopDevelopmentConfiguration() {
        setRuntime("community", "DESKTOP", "dev");
        System.setProperty(DesktopUpdateSourceFactory.DEVELOPMENT_DIRECTORY_PROPERTY,
                temporaryDirectory.toString());

        DevelopmentFileUpdateSource source = assertInstanceOf(DevelopmentFileUpdateSource.class,
                DesktopUpdateSourceFactory.create());

        assertEquals(temporaryDirectory.toAbsolutePath().normalize(), source.rootDirectory());
    }

    @Test
    void releaseRuntimeIgnoresDevelopmentDirectory() {
        setRuntime("community", "DESKTOP", "release");
        System.setProperty(DesktopUpdateSourceFactory.DEVELOPMENT_DIRECTORY_PROPERTY,
                temporaryDirectory.toString());

        assertInstanceOf(GitHubReleaseUpdateSource.class, DesktopUpdateSourceFactory.create());
    }

    @Test
    void nonCommunityOrNonDesktopRuntimeIgnoresDevelopmentDirectory() {
        System.setProperty(DesktopUpdateSourceFactory.DEVELOPMENT_DIRECTORY_PROPERTY,
                temporaryDirectory.toString());
        setRuntime("pro", "DESKTOP", "dev");
        assertInstanceOf(GitHubReleaseUpdateSource.class, DesktopUpdateSourceFactory.create());

        setRuntime("community", "WEB", "dev");
        assertInstanceOf(GitHubReleaseUpdateSource.class, DesktopUpdateSourceFactory.create());
    }

    @Test
    void rejectsRelativeDevelopmentDirectoryInCommunityDesktopDevelopment() {
        setRuntime("community", "DESKTOP", "dev");
        System.setProperty(DesktopUpdateSourceFactory.DEVELOPMENT_DIRECTORY_PROPERTY, "fixtures/updates");

        var exception = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                DesktopUpdateSourceFactory::create);
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("absolute path"));
    }

    @Test
    void rejectsMissingOrNonDirectoryDevelopmentPath() throws Exception {
        setRuntime("community", "DESKTOP", "dev");
        Path missing = temporaryDirectory.resolve("missing");
        System.setProperty(DesktopUpdateSourceFactory.DEVELOPMENT_DIRECTORY_PROPERTY, missing.toString());
        assertThrows(IllegalArgumentException.class, DesktopUpdateSourceFactory::create);

        Path file = temporaryDirectory.resolve("fixture.txt");
        java.nio.file.Files.writeString(file, "fixture");
        System.setProperty(DesktopUpdateSourceFactory.DEVELOPMENT_DIRECTORY_PROPERTY, file.toString());
        assertThrows(IllegalArgumentException.class, DesktopUpdateSourceFactory::create);
    }

    @Test
    void blankDevelopmentDirectoryFallsBackToGithub() {
        setRuntime("community", "DESKTOP", "dev");
        System.setProperty(DesktopUpdateSourceFactory.DEVELOPMENT_DIRECTORY_PROPERTY, "   ");
        assertInstanceOf(GitHubReleaseUpdateSource.class, DesktopUpdateSourceFactory.create());
    }

    private static void setRuntime(String runtimeMode, String desktopMode, String profile) {
        System.setProperty("chat2db.runtime.mode", runtimeMode);
        System.setProperty("chat2db.mode", desktopMode);
        System.setProperty("spring.profiles.active", profile);
    }
}
