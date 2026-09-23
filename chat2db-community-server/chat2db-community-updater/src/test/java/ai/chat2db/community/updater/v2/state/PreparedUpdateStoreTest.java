package ai.chat2db.community.updater.v2.state;

import ai.chat2db.community.updater.v2.enums.ReleaseStatusEnum;
import ai.chat2db.community.updater.v2.enums.UpdateArchitectureEnum;
import ai.chat2db.community.updater.v2.enums.UpdateChannelEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePackageTypeEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePlatformEnum;
import ai.chat2db.community.updater.v2.enums.UpdateScopeEnum;
import ai.chat2db.community.updater.v2.installation.UpdateLayout;
import ai.chat2db.community.updater.v2.model.UpdateManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedUpdateStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsThePreparedUpdate() {
        PreparedUpdateStore store = new PreparedUpdateStore(layout());
        UpdateManifest manifest = manifest();

        store.save("tx-prepared", manifest);
        Optional<PreparedUpdateStore.PreparedUpdate> loaded = store.load();

        assertTrue(loaded.isPresent(), "a saved prepared update must survive a restart");
        assertEquals("tx-prepared", loaded.orElseThrow().transactionId());
        assertEquals(manifest, loaded.orElseThrow().manifest(),
            "the whole signed manifest must round trip so it can be verified offline");
    }

    @Test
    void missingRecordMeansNoPreparedUpdate() {
        assertTrue(new PreparedUpdateStore(layout()).load().isEmpty());
    }

    @Test
    void corruptRecordMeansNoPreparedUpdate() throws Exception {
        PreparedUpdateStore store = new PreparedUpdateStore(layout());
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), "{not json");

        assertTrue(store.load().isEmpty(), "an unreadable record must never fail the update check");
    }

    @Test
    void recordWithoutTransactionIdMeansNoPreparedUpdate() throws Exception {
        PreparedUpdateStore store = new PreparedUpdateStore(layout());
        Files.createDirectories(store.file().getParent());
        new ObjectMapper().writeValue(store.file().toFile(), Map.of("manifest", manifest()));

        assertTrue(store.load().isEmpty());
    }

    @Test
    void recordWithoutManifestMeansNoPreparedUpdate() throws Exception {
        PreparedUpdateStore store = new PreparedUpdateStore(layout());
        Files.createDirectories(store.file().getParent());
        new ObjectMapper().writeValue(store.file().toFile(), Map.of("transactionId", "tx-prepared"));

        assertTrue(store.load().isEmpty());
    }

    @Test
    void clearRemovesTheRecord() {
        PreparedUpdateStore store = new PreparedUpdateStore(layout());
        store.save("tx-prepared", manifest());

        store.clear();
        store.clear();

        assertFalse(Files.exists(store.file()));
        assertTrue(store.load().isEmpty());
    }

    @Test
    void saveLeavesNoTemporaryFileBehind() {
        PreparedUpdateStore store = new PreparedUpdateStore(layout());
        store.save("tx-prepared", manifest());

        assertFalse(Files.exists(store.file().resolveSibling("prepared-update.json.tmp")));
    }

    private UpdateLayout layout() {
        Path install = temporaryDirectory.resolve("Applications/Chat2DB Community.app");
        return new UpdateLayout(
            install,
            install.resolve("Contents/app"),
            temporaryDirectory.resolve("cache"),
            temporaryDirectory.resolve("support")
        );
    }

    private static UpdateManifest manifest() {
        return new UpdateManifest(
            2, 101, ReleaseStatusEnum.ACTIVE, "COMMUNITY", UpdateChannelEnum.STABLE, "5.3.4", "5.3.401", "sha",
            UpdatePlatformEnum.MACOS, UpdateArchitectureEnum.ARM64, UpdateScopeEnum.FULL_PACKAGE,
            UpdatePackageTypeEnum.MACOS_APP_ARCHIVE, "https://example.com/package.tar.gz", 1024,
            "a".repeat(64), "bin/chat2db", 3, 3, "https://example.com/notes", "release", "signature"
        );
    }
}
