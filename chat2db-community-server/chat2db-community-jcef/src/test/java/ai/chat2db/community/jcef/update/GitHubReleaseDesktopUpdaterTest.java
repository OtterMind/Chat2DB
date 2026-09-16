package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import ai.chat2db.community.updater.v2.enums.UpdateChannelEnum;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseDesktopUpdaterTest {
    @Test
    void resolvesIndependentStableAndBetaIndexes() {
        assertEquals("https://github.com/OtterMind/Chat2DB/releases/latest/download/release-index.json",
            GitHubReleaseDesktopUpdater.indexUrl(UpdateChannelEnum.STABLE));
        assertEquals("https://github.com/OtterMind/Chat2DB/releases/download/community-beta/release-index.json",
            GitHubReleaseDesktopUpdater.indexUrl(UpdateChannelEnum.BETA));
        assertTrue(GitHubReleaseDesktopUpdater.isAllowedUrl(
            URI.create(GitHubReleaseDesktopUpdater.indexUrl(UpdateChannelEnum.BETA))));
    }

    @Test
    void permitsOfficialReleaseAndAssetRedirects() {
        assertTrue(GitHubReleaseDesktopUpdater.isAllowedUrl(URI.create(GitHubReleaseDesktopUpdater.INDEX_URL)));
        assertTrue(GitHubReleaseDesktopUpdater.isAllowedUrl(URI.create(
            "https://release-assets.githubusercontent.com/asset?signature=example")));
        assertTrue(GitHubReleaseDesktopUpdater.isAllowedUrl(URI.create(
            "https://objects.githubusercontent.com/asset")));
    }

    @Test
    void rejectsOtherRepositoriesAndUntrustedRedirects() {
        for (String url : java.util.List.of(
                "https://github.com/other/repository/releases/download/v1/package",
                "http://github.com/OtterMind/Chat2DB/releases/latest",
                "https://github.com.evil.example/OtterMind/Chat2DB/releases/latest",
                "https://user@github.com/OtterMind/Chat2DB/releases/latest",
                "https://github.com:8443/OtterMind/Chat2DB/releases/latest",
                "https://github.com/OtterMind/Chat2DB/releases/latest#fragment")) {
            assertFalse(GitHubReleaseDesktopUpdater.isAllowedUrl(URI.create(url)), url);
        }
    }
}
