package ai.chat2db.community.sqlx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqlxPlatformTest {

    @Test
    void mapsSupportedOperatingSystemsAndArchitectures() {
        assertEquals(Optional.of("sqlx-macos-arm64.zip"),
                SqlxPlatform.of("Mac OS X", "aarch64").map(SqlxPlatform::assetName));
        assertEquals(Optional.of("sqlx-macos-x64.zip"),
                SqlxPlatform.of("Darwin", "x86_64").map(SqlxPlatform::assetName));
        assertEquals(Optional.of("sqlx-linux-arm64.zip"),
                SqlxPlatform.of("Linux", "arm64").map(SqlxPlatform::assetName));
        assertEquals(Optional.of("sqlx-linux-x64.zip"),
                SqlxPlatform.of("Linux", "amd64").map(SqlxPlatform::assetName));
    }

    @Test
    void windowsOnlyShipsAnX64Archive() {
        assertEquals(Optional.of("sqlx-windows-x64.zip"),
                SqlxPlatform.of("Windows 11", "amd64").map(SqlxPlatform::assetName));
        assertEquals(Optional.empty(), SqlxPlatform.of("Windows 11", "aarch64"));
    }

    @Test
    void unknownSystemsHaveNoArchive() {
        assertEquals(Optional.empty(), SqlxPlatform.of("FreeBSD", "x86_64"));
        assertEquals(Optional.empty(), SqlxPlatform.of("Linux", "riscv64"));
    }

    @Test
    void executableNameFollowsTheOperatingSystem() {
        assertEquals("sqlx.exe", SqlxPlatform.of("Windows 11", "x64").orElseThrow().executableName());
        assertEquals("sqlx", SqlxPlatform.of("Mac OS X", "arm64").orElseThrow().executableName());
    }

    @Test
    void rendererPlatformNameIsAlwaysAvailable() {
        String platform = SqlxPlatform.rendererPlatform();
        assertTrue(platform.equals("mac") || platform.equals("windows") || platform.equals("linux"), platform);
        assertFalse(SqlxPlatform.defaultInstallDirectory().toString().isBlank());
        assertFalse(SqlxPlatform.defaultExecutableName().isBlank());
    }
}
