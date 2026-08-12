package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.annotation.NotCliRuntime;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
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
        Updater updater = Updater.getInstance();
        Field appDirectoryField = field("APP_DIR");
        Field temporaryDirectoryField = field("TMP_DIR");
        Object originalAppDirectory = appDirectoryField.get(updater);
        Object originalTemporaryDirectory = temporaryDirectoryField.get(updater);
        String propertyName = "chat2db.jcef.web-frontend";
        String originalProperty = System.getProperty(propertyName);
        Path temporaryFile = temporaryDirectory.resolve("downloads/update.jar");
        Path backupSession = Files.createDirectories(temporaryDirectory.resolve(".chat2db-update-backups/old-session"));
        Files.createDirectories(temporaryFile.getParent());
        Files.writeString(temporaryFile, "keep");
        Files.writeString(backupSession.resolve(".owner-pid"), "0");
        try {
            appDirectoryField.set(updater, temporaryDirectory);
            temporaryDirectoryField.set(updater, temporaryFile.getParent());
            System.setProperty(propertyName, "true");

            assertThrows(BusinessException.class, () -> updater.triggerDownload(new ConsoleResult()));
            assertThrows(BusinessException.class, updater::triggerInstallation);

            assertTrue(Files.exists(temporaryFile));
            assertTrue(Files.exists(backupSession));
        } finally {
            appDirectoryField.set(updater, originalAppDirectory);
            temporaryDirectoryField.set(updater, originalTemporaryDirectory);
            if (originalProperty == null) {
                System.clearProperty(propertyName);
            } else {
                System.setProperty(propertyName, originalProperty);
            }
        }
    }

    @Test
    void rejectsSecondDownloadAndInstallationWhileTheFirstOperationIsActive() throws Exception {
        Updater updater = Updater.getInstance();
        Field downloadInProgressField = field("downloadInProgress");
        Field installationInProgressField = field("installationInProgress");
        Object originalDownloadInProgress = downloadInProgressField.get(updater);
        Object originalInstallationInProgress = installationInProgressField.get(updater);
        String originalProfile = System.getProperty("spring.profiles.active");
        String originalWebFrontend = System.getProperty("chat2db.jcef.web-frontend");
        try {
            System.setProperty("spring.profiles.active", "release");
            System.setProperty("chat2db.jcef.web-frontend", "false");

            downloadInProgressField.set(updater, true);
            BusinessException downloadException = assertThrows(BusinessException.class,
                    () -> updater.triggerDownload(new ConsoleResult()));
            assertEquals("Another update operation is already in progress.", downloadException.getMessage());

            downloadInProgressField.set(updater, false);
            installationInProgressField.set(updater, true);
            BusinessException installationException = assertThrows(BusinessException.class, updater::triggerInstallation);
            assertEquals("Update installation is already in progress.", installationException.getMessage());
        } finally {
            downloadInProgressField.set(updater, originalDownloadInProgress);
            installationInProgressField.set(updater, originalInstallationInProgress);
            restoreProperty("spring.profiles.active", originalProfile);
            restoreProperty("chat2db.jcef.web-frontend", originalWebFrontend);
        }
    }

    @Test
    void installationGuardIsReleasedWhenPreparationFails() throws Exception {
        Updater updater = Updater.getInstance();
        Field checkResultField = field("checkResult");
        Field updateReadyToInstallField = field("updateReadyToInstall");
        Field installationInProgressField = field("installationInProgress");
        Field progressDialogField = field("progressDialog");
        Object originalCheckResult = checkResultField.get(updater);
        Object originalUpdateReadyToInstall = updateReadyToInstallField.get(updater);
        Object originalInstallationInProgress = installationInProgressField.get(updater);
        Object originalProgressDialog = progressDialogField.get(updater);
        String originalProfile = System.getProperty("spring.profiles.active");
        String originalWebFrontend = System.getProperty("chat2db.jcef.web-frontend");
        try {
            System.setProperty("spring.profiles.active", "release");
            System.setProperty("chat2db.jcef.web-frontend", "false");
            checkResultField.set(updater, new Updater.CheckResult());
            updateReadyToInstallField.set(updater, true);
            installationInProgressField.set(updater, false);
            progressDialogField.set(updater, new Updater.UpdateProgressDialog());

            assertFalse(updater.triggerInstallation());
            var exception = assertThrows(java.io.IOException.class, updater::requireUpdateActions);
            assertEquals("Update plan is incomplete: update actions are missing.", exception.getMessage());
            assertFalse((boolean) installationInProgressField.get(updater));
        } finally {
            checkResultField.set(updater, originalCheckResult);
            updateReadyToInstallField.set(updater, originalUpdateReadyToInstall);
            installationInProgressField.set(updater, originalInstallationInProgress);
            progressDialogField.set(updater, originalProgressDialog);
            restoreProperty("spring.profiles.active", originalProfile);
            restoreProperty("chat2db.jcef.web-frontend", originalWebFrontend);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = Updater.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void restoreProperty(String propertyName, String originalValue) {
        if (originalValue == null) {
            System.clearProperty(propertyName);
        } else {
            System.setProperty(propertyName, originalValue);
        }
    }
}
