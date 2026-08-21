package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.console.ConsoleResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopUpdaterRegistryTest {

    @AfterEach
    void tearDown() {
        DesktopUpdaterRegistry.resetForTests();
        System.clearProperty("chat2db.runtime.mode");
        System.clearProperty("chat2db.mode");
        System.clearProperty("chat2db.gui");
        System.clearProperty("spring.profiles.active");
        System.clearProperty("chat2db.jcef.web-frontend");
    }

    @Test
    void communityDesktopUsesSecureUpdateWorkflowAdapter() {
        System.setProperty("chat2db.runtime.mode", "community");
        System.setProperty("chat2db.mode", "DESKTOP");

        assertInstanceOf(CommunityDesktopUpdater.class, DesktopUpdaterRegistry.get());
    }

    @Test
    void headlessRuntimeUsesNoOpUpdater() {
        System.setProperty("chat2db.runtime.mode", "community");
        System.setProperty("chat2db.mode", "WEB");

        IDesktopUpdater updater = DesktopUpdaterRegistry.get();

        assertInstanceOf(NoOpDesktopUpdater.class, updater);
        assertFalse(updater.appCheckUpdate().needsUpdate());
    }

    @Test
    void developmentDesktopCanCheckButCannotDownloadOrInstall() throws Exception {
        System.setProperty("chat2db.runtime.mode", "community");
        System.setProperty("chat2db.mode", "DESKTOP");
        System.setProperty("spring.profiles.active", "dev");
        System.setProperty("chat2db.jcef.web-frontend", "false");

        IDesktopUpdater updater = DesktopUpdaterRegistry.get();

        assertInstanceOf(CommunityDesktopUpdater.class, updater);
        assertFalse(updater.triggerDownload(new ConsoleResult()));
        assertFalse(updater.triggerInstallation());
    }

    @Test
    void registeredProductUpdaterOverridesDefault() {
        IDesktopUpdater updater = new StubDesktopUpdater();

        DesktopUpdaterRegistry.register(updater);

        assertSame(updater, DesktopUpdaterRegistry.get());
    }

    @Test
    void rejectsNullRegistration() {
        assertThrows(NullPointerException.class, () -> DesktopUpdaterRegistry.register(null));
    }

    private static final class StubDesktopUpdater implements IDesktopUpdater {

        @Override
        public DesktopUpdateCheckResult appCheckUpdate() {
            return DesktopUpdateCheckResult.notAvailable();
        }

        @Override
        public boolean triggerDownload(ConsoleResult consoleResult) {
            return false;
        }

        @Override
        public boolean triggerInstallation() {
            return false;
        }

        @Override
        public boolean prepareRestart() {
            return false;
        }

        @Override
        public void exitCurrentProcessAfterResponse() {
        }

        @Override
        public boolean setBetaEnabled(boolean enabled) {
            return enabled;
        }

        @Override
        public boolean isBetaEnabled() {
            return true;
        }
    }
}
