package ai.chat2db.community.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Redirects user.home to a fresh temp directory so file-backed storages
 * do not touch the developer's real ~/.chat2db* data. Must be referenced
 * before any storage class is loaded.
 */
public final class TestHome {

    static {
        try {
            Path dir = Files.createTempDirectory("chat2db-test-home");
            System.setProperty("user.home", dir.toString());
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestHome() {
    }

    public static void init() {
        // no-op; the static initializer does the work
    }
}
