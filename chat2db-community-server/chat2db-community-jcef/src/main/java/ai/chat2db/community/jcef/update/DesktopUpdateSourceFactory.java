package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.util.ConfigUtils;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Files;

/** Selects the fixed production source or an explicitly enabled development fixture directory. */
@Slf4j
final class DesktopUpdateSourceFactory {

    static final String DEVELOPMENT_DIRECTORY_PROPERTY = "chat2db.jcef.dev-update-directory";

    private DesktopUpdateSourceFactory() {
    }

    static UpdateSource create() {
        return create(ConfigUtils.isCommunity(), ConfigUtils.isDesktop(), ConfigUtils.isRelease());
    }

    static UpdateSource create(boolean communityRuntime, boolean desktopRuntime, boolean releaseRuntime) {
        String configuredDirectory = System.getProperty(DEVELOPMENT_DIRECTORY_PROPERTY);
        boolean developmentCommunityDesktop = communityRuntime && desktopRuntime && !releaseRuntime;
        if (configuredDirectory != null && !configuredDirectory.isBlank() && developmentCommunityDesktop) {
            Path directory = Path.of(configuredDirectory.trim()).normalize();
            if (!directory.isAbsolute()) {
                throw new IllegalArgumentException(DEVELOPMENT_DIRECTORY_PROPERTY + " must be an absolute path: " + configuredDirectory);
            }
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException(DEVELOPMENT_DIRECTORY_PROPERTY + " must point to an existing directory: " + directory);
            }
            log.warn("Using development-only local desktop update source: {}", directory);
            return new DevelopmentFileUpdateSource(directory);
        }
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            log.warn("Ignoring {} outside Community Desktop development mode", DEVELOPMENT_DIRECTORY_PROPERTY);
        }
        return new GitHubReleaseUpdateSource();
    }
}
