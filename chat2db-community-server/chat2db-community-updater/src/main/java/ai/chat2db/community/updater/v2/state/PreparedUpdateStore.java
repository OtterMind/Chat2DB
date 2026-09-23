package ai.chat2db.community.updater.v2.state;

import ai.chat2db.community.updater.v2.installation.UpdateLayout;
import ai.chat2db.community.updater.v2.model.UpdateManifest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Remembers a downloaded and staged update across application restarts.
 *
 * <p>The persisted manifest is the signed one, so a later session can verify it
 * offline with the bundled public key and only then trust the package that is
 * still in the cache. A missing, unreadable or incomplete file simply means "no
 * prepared update", never an error.</p>
 */
public final class PreparedUpdateStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UpdateLayout layout;

    public PreparedUpdateStore(UpdateLayout layout) {
        this.layout = layout;
    }

    public record PreparedUpdate(String transactionId, UpdateManifest manifest) {
    }

    public Path file() {
        return layout.preparedUpdateFile();
    }

    public void save(String transactionId, UpdateManifest manifest) {
        Path target = file();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(temporary.toFile(), new PreparedUpdate(transactionId, manifest));
            try {
                Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception unwritable) {
            // A prepared update that cannot be remembered still installs in this session.
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (Exception ignored) {
                // Best effort cleanup of the temporary file.
            }
        }
    }

    public Optional<PreparedUpdate> load() {
        Path source = file();
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            PreparedUpdate prepared = OBJECT_MAPPER.readValue(source.toFile(), PreparedUpdate.class);
            if (prepared == null || prepared.manifest() == null
                    || prepared.transactionId() == null || prepared.transactionId().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(prepared);
        } catch (Exception unreadableOrStale) {
            return Optional.empty();
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file());
        } catch (Exception ignored) {
            // A stale record is harmless: it is validated again before it is used.
        }
    }
}
