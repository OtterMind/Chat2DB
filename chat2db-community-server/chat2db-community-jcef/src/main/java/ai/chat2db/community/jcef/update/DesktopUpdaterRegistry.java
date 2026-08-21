package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.util.ConfigUtils;

import java.util.Objects;

public final class DesktopUpdaterRegistry {

    private static final IDesktopUpdater NO_OP_UPDATER = new NoOpDesktopUpdater();

    private static volatile IDesktopUpdater registeredUpdater;
    private static volatile IDesktopUpdater communityUpdater;

    private DesktopUpdaterRegistry() {
    }

    public static IDesktopUpdater get() {
        IDesktopUpdater updater = registeredUpdater;
        if (updater != null) {
            return updater;
        }
        if (ConfigUtils.isCommunity() && ConfigUtils.isDesktop() && ConfigUtils.isShowGUI()) {
            return communityUpdater();
        }
        return NO_OP_UPDATER;
    }

    public static void register(IDesktopUpdater updater) {
        registeredUpdater = Objects.requireNonNull(updater, "Desktop updater is required");
    }

    static void resetForTests() {
        registeredUpdater = null;
        communityUpdater = null;
    }

    private static IDesktopUpdater communityUpdater() {
        IDesktopUpdater updater = communityUpdater;
        if (updater == null) {
            synchronized (DesktopUpdaterRegistry.class) {
                updater = communityUpdater;
                if (updater == null) {
                    updater = new GitHubReleaseDesktopUpdater();
                    communityUpdater = updater;
                }
            }
        }
        return updater;
    }
}
