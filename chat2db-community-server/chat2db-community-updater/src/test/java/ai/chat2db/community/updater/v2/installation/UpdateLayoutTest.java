package ai.chat2db.community.updater.v2.installation;

import ai.chat2db.community.updater.v2.enums.UpdatePackageTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateLayoutTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void usesMacosUserLibraryAndIsolatesProducts() {
        String previousHome = System.getProperty("user.home");
        String previousOs = System.getProperty("os.name");
        try {
            System.setProperty("user.home", temporaryDirectory.toString());
            System.setProperty("os.name", "Mac OS X");
            Path applications = temporaryDirectory.resolve("Applications");

            UpdateLayout pro = new UpdateLayout(applications.resolve("Chat2DB Community.app"), "COMMUNITY");
            UpdateLayout local = new UpdateLayout(applications.resolve("Other App.app"), "OTHER");

            assertEquals(temporaryDirectory.resolve("Library/Caches/Chat2DB/Updater/community"), pro.cacheRoot());
            assertEquals(temporaryDirectory.resolve("Library/Application Support/Chat2DB/Updater/community"),
                pro.supportRoot());
            assertEquals(temporaryDirectory.resolve("Library/Caches/Chat2DB/Updater/other"), local.cacheRoot());
            assertEquals(temporaryDirectory.resolve("Library/Application Support/Chat2DB/Updater/other"),
                local.supportRoot());
            assertFalse(pro.cacheRoot().equals(local.cacheRoot()));
            assertFalse(pro.supportRoot().equals(local.supportRoot()));
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("os.name", previousOs);
        }
    }

    @Test
    void keepsTheRollbackCopyNextToTheInstallTarget() {
        UpdateLayout layout = new UpdateLayout(Path.of("Applications", "Chat2DB Community.app"), "COMMUNITY");

        Path backup = layout.previousPackage();

        assertEquals(layout.installTarget().resolveSibling("Chat2DB Community.app.chat2db-previous"), backup);
        assertEquals("Chat2DB Community.app.chat2db-previous", backup.getFileName().toString());
        assertEquals(layout.installTarget().getParent(), backup.getParent());
    }

    @Test
    void keepsEveryTransactionArtifactUnderOneCacheDirectory() {
        UpdateLayout layout = testLayout("COMMUNITY");

        Path workspace = layout.updateWorkspace();

        assertEquals(workspace.resolve("package.tar.gz"),
            layout.cachedPackage(UpdatePackageTypeEnum.MACOS_APP_ARCHIVE));
        assertEquals(workspace.resolve("candidate"), layout.stagingDirectory());
        assertEquals(workspace.resolve("helper"), layout.workDirectory());
        assertEquals(workspace.resolve("health-tx-1.json"), layout.healthFile("tx-1"));
        assertEquals(workspace, layout.updateWorkspace());
        assertThrows(IllegalArgumentException.class, () -> layout.healthFile("../other"));
    }

    private UpdateLayout testLayout(String product) {
        Path install = temporaryDirectory.resolve("Applications/Chat2DB Community.app");
        String productName = product.toLowerCase();
        return new UpdateLayout(
            install,
            install.resolve("Contents/app"),
            temporaryDirectory.resolve("Library/Caches/Chat2DB/Updater").resolve(productName),
            temporaryDirectory.resolve("Library/Application Support/Chat2DB/Updater").resolve(productName)
        );
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
