package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns local_version.json persistence and its recovery behavior. */
final class LocalVersionStore {

    @FunctionalInterface
    interface MetadataSerializer {
        void write(OutputStream output, VersionMetadata metadata) throws IOException;
    }

    @FunctionalInterface
    interface FileMover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    private final Path versionFile;
    private final ObjectMapper objectMapper;
    private final MetadataSerializer serializer;
    private final FileMover fileMover;
    private final Consumer<String> progressLog;
    private final Consumer<String> errorLog;

    LocalVersionStore(Path versionFile, ObjectMapper objectMapper, Consumer<String> progressLog,
                      Consumer<String> errorLog) {
        this(versionFile, objectMapper,
                (output, metadata) -> objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, metadata),
                progressLog, errorLog, Files::move);
    }

    LocalVersionStore(Path versionFile, ObjectMapper objectMapper, MetadataSerializer serializer,
                      Consumer<String> progressLog, Consumer<String> errorLog) {
        this(versionFile, objectMapper, serializer, progressLog, errorLog, Files::move);
    }

    LocalVersionStore(Path versionFile, ObjectMapper objectMapper, MetadataSerializer serializer,
                      Consumer<String> progressLog, Consumer<String> errorLog, FileMover fileMover) {
        this.versionFile = Objects.requireNonNull(versionFile, "versionFile is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.serializer = Objects.requireNonNull(serializer, "serializer is required");
        this.progressLog = Objects.requireNonNull(progressLog, "progressLog is required");
        this.errorLog = Objects.requireNonNull(errorLog, "errorLog is required");
        this.fileMover = Objects.requireNonNull(fileMover, "fileMover is required");
    }

    VersionMetadata load(boolean repairCorruptFile) {
        if (!Files.exists(versionFile)) {
            progressLog.accept("Local version file not found: " + versionFile);
            return null;
        }
        progressLog.accept("Loading local version from: " + versionFile);
        try (InputStream input = Files.newInputStream(versionFile)) {
            return objectMapper.readValue(input, VersionMetadata.class);
        } catch (Exception exception) {
            String message = "Failed to load local_version.json: " + exception.getMessage()
                    + ". Assuming no local version.";
            progressLog.accept("ERROR: " + message);
            errorLog.accept(message);
            if (repairCorruptFile) {
                repairCorruptVersionFile();
            }
            return null;
        }
    }

    void save(VersionMetadata metadata) throws IOException {
        String message = "Saving local_version.json for version " + metadata.version + " to: "
                + versionFile.toAbsolutePath();
        progressLog.accept(message);
        Path directory = versionFile.toAbsolutePath().getParent();
        if (directory == null) {
            throw new IOException("local_version.json has no parent directory");
        }
        Files.createDirectories(directory);
        Path temporaryFile = Files.createTempFile(directory, versionFile.getFileName() + ".", ".tmp");
        boolean committed = false;
        try {
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                serializer.write(output, metadata);
            }
            moveIntoPlace(temporaryFile, versionFile);
            committed = true;
        } finally {
            if (!committed) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    private void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            fileMover.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailure) {
            try {
                fileMover.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(atomicMoveFailure);
                throw fallbackFailure;
            }
        }
    }

    private void repairCorruptVersionFile() {
        try {
            Files.move(versionFile, versionFile.resolveSibling("local_version.json.corrupted_"
                    + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            errorLog.accept("Could not rename corrupted local_version.json: " + exception.getMessage());
        }
    }
}
