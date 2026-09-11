package ai.chat2db.community.storage.agent;

import ai.chat2db.community.storage.StorageFileUtils;
import ai.chat2db.community.tools.exception.storage.StorageException;
import com.alibaba.fastjson2.JSON;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

final class AgentSnapshotStorage<T> {

    private final AgentV2StoragePaths paths;
    private final StorageFileUtils storageFileUtils;
    private final String resourceName;
    private final Class<T> type;
    private final Function<T, String> id;
    private final Function<T, String> sessionId;
    private final Consumer<T> validator;

    AgentSnapshotStorage(
            AgentV2StoragePaths paths,
            StorageFileUtils storageFileUtils,
            String resourceName,
            Class<T> type,
            Function<T, String> id,
            Function<T, String> sessionId,
            Consumer<T> validator) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.storageFileUtils = Objects.requireNonNull(storageFileUtils, "storageFileUtils");
        this.resourceName = Objects.requireNonNull(resourceName, "resourceName");
        this.type = Objects.requireNonNull(type, "type");
        this.id = Objects.requireNonNull(id, "id");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    T create(T value) {
        validate(value);
        Path directory = paths.resourceDirectory(sessionId.apply(value), resourceName);
        Path file = paths.resourceFile(sessionId.apply(value), resourceName, id.apply(value));
        storageFileUtils.createPrivateDirectory(directory);
        storageFileUtils.verifyInsideRoot(paths.root(), directory);
        storageFileUtils.rejectSymbolicLink(file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException(resourceName + " record already exists: " + id.apply(value));
        }
        storageFileUtils.writeAtomically(file, JSON.toJSONString(value));
        return value;
    }

    T get(String ownerSessionId, String resourceId) {
        Path file = paths.resourceFile(ownerSessionId, resourceName, resourceId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return read(file, ownerSessionId);
    }

    List<T> list(String ownerSessionId) {
        Path directory = paths.resourceDirectory(ownerSessionId, resourceName);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        storageFileUtils.rejectSymbolicLink(directory);
        storageFileUtils.verifyInsideRoot(paths.root(), directory);
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .peek(storageFileUtils::rejectSymbolicLink)
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> read(path, ownerSessionId))
                    .toList();
        } catch (IOException exception) {
            throw new StorageException("Failed to list " + resourceName + " records", exception);
        }
    }

    T update(T value) {
        validate(value);
        Path file = paths.resourceFile(sessionId.apply(value), resourceName, id.apply(value));
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException(resourceName + " record does not exist: " + id.apply(value));
        }
        storageFileUtils.rejectSymbolicLink(file);
        storageFileUtils.verifyInsideRoot(paths.root(), file);
        storageFileUtils.writeAtomically(file, JSON.toJSONString(value));
        return value;
    }

    private T read(Path file, String ownerSessionId) {
        storageFileUtils.rejectSymbolicLink(file);
        storageFileUtils.verifyInsideRoot(paths.root(), file);
        try {
            T value = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8), type);
            validate(value);
            if (!Objects.equals(ownerSessionId, sessionId.apply(value))) {
                throw new StorageException(resourceName + " record belongs to another session");
            }
            return value;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof StorageException storageException) {
                throw storageException;
            }
            throw new StorageException("Failed to read " + resourceName + " record", exception);
        }
    }

    private void validate(T value) {
        Objects.requireNonNull(value, "value");
        validator.accept(value);
        paths.resourceFile(sessionId.apply(value), resourceName, id.apply(value));
    }
}
