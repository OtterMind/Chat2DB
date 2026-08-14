package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.annotation.NotCliRuntime;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterCliRuntimeBoundaryTest {

    @Test
    void updaterIsExcludedFromCliRuntime() {
        assertTrue(Updater.class.isAnnotationPresent(NotCliRuntime.class), Updater.class.getName());
    }

    @Test
    void selfUpdateRequiresAnInstalledReleaseInsteadOfTheDevelopmentFrontend() {
        assertTrue(Updater.isSelfUpdateSupported(true, false));
        assertFalse(Updater.isSelfUpdateSupported(false, false));
        assertFalse(Updater.isSelfUpdateSupported(true, true));
    }

    @Test
    void developmentFrontendIsRejectedBeforeDownloadOrInstallationTouchesFiles(@TempDir Path temporaryDirectory) throws Exception {
        Updater updater = newAvailableUpdater(temporaryDirectory);
        String propertyName = "chat2db.jcef.web-frontend";
        String originalProperty = System.getProperty(propertyName);
        Path temporaryFile = temporaryDirectory.resolve("downloads/update.jar");
        Path backupSession = Files.createDirectories(temporaryDirectory.resolve(".chat2db-update-backups/old-session"));
        Files.createDirectories(temporaryFile.getParent());
        Files.writeString(temporaryFile, "keep");
        Files.writeString(backupSession.resolve(".owner-pid"), "0");
        try {
            System.setProperty(propertyName, "true");

            assertThrows(BusinessException.class, () -> updater.triggerDownload(new ConsoleResult()));
            assertThrows(BusinessException.class, updater::triggerInstallation);

            assertTrue(Files.exists(temporaryFile));
            assertTrue(Files.exists(backupSession));
        } finally {
            if (originalProperty == null) {
                System.clearProperty(propertyName);
            } else {
                System.setProperty(propertyName, originalProperty);
            }
        }
    }

    @Test
    void rejectsSecondDownloadAndInstallationWhileTheFirstOperationIsActive(@TempDir Path temporaryDirectory) throws Exception {
        Updater updater = newAvailableUpdater(temporaryDirectory);
        String originalProfile = System.getProperty("spring.profiles.active");
        String originalWebFrontend = System.getProperty("chat2db.jcef.web-frontend");
        try {
            System.setProperty("spring.profiles.active", "release");
            System.setProperty("chat2db.jcef.web-frontend", "false");

            updater.operationCoordinator().beginDownload();
            BusinessException downloadException = assertThrows(BusinessException.class,
                    () -> updater.triggerDownload(new ConsoleResult()));
            assertEquals("Another update operation is already in progress.", downloadException.getMessage());

            updater.operationCoordinator().finishDownloadAfterFailure();
            updater.operationCoordinator().beginDownload();
            updater.operationCoordinator().markDownloadReady();
            updater.operationCoordinator().beginInstallation();
            BusinessException installationException = assertThrows(BusinessException.class, updater::triggerInstallation);
            assertEquals("Update installation is already in progress.", installationException.getMessage());
        } finally {
            updater.operationCoordinator().finishInstallationAfterFailure();
            restoreProperty("spring.profiles.active", originalProfile);
            restoreProperty("chat2db.jcef.web-frontend", originalWebFrontend);
        }
    }

    @Test
    void installationGuardIsReleasedWhenPreparationFails(@TempDir Path temporaryDirectory) throws Exception {
        Updater updater = newAvailableUpdater(temporaryDirectory);
        String originalProfile = System.getProperty("spring.profiles.active");
        String originalWebFrontend = System.getProperty("chat2db.jcef.web-frontend");
        try {
            System.setProperty("spring.profiles.active", "release");
            System.setProperty("chat2db.jcef.web-frontend", "false");
            updater.operationCoordinator().beginDownload();
            updater.operationCoordinator().markDownloadReady();
            updater.operationCoordinator().replaceCheckResult(previous -> new Updater.CheckResult());

            assertFalse(updater.triggerInstallation());
            var exception = assertThrows(java.io.IOException.class, updater::requireUpdateActions);
            assertEquals("Update plan is incomplete: update actions are missing.", exception.getMessage());
            assertEquals(UpdateOperationCoordinator.State.AVAILABLE, updater.operationCoordinator().currentState());
        } finally {
            restoreProperty("spring.profiles.active", originalProfile);
            restoreProperty("chat2db.jcef.web-frontend", originalWebFrontend);
        }
    }

    private static Updater newAvailableUpdater(Path directory) {
        FakeUpdateSource source = new FakeUpdateSource().manifest("""
                {
                  "version": "5.3.2",
                  "releasePageUrl": "https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.2",
                  "forceUpdate": false,
                  "files": []
                }
                """);
        Updater updater = new Updater(source, directory, directory.resolve("local_version.json"),
                directory.resolve("downloads"), System::nanoTime);
        assertTrue(updater.appCheckUpdate().isNeedsUpdate());
        return updater;
    }

    private static void restoreProperty(String propertyName, String originalValue) {
        if (originalValue == null) {
            System.clearProperty(propertyName);
        } else {
            System.setProperty(propertyName, originalValue);
        }
    }
}
