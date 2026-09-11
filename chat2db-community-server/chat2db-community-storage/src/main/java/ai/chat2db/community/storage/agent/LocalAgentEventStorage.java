package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.service.agent.AgentEventStorage;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.storage.StorageFileUtils;
import ai.chat2db.community.tools.exception.storage.StorageException;
import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Component
public class LocalAgentEventStorage implements AgentEventStorage {

    private static final int MAX_PAGE_SIZE = 1000;

    private final AgentV2StoragePaths paths;
    private final StorageFileUtils storageFileUtils;
    private final AgentStorageOwnership ownership;
    private final Map<String, Long> lastSequences = new HashMap<>();

    public LocalAgentEventStorage(
            AgentV2StoragePaths paths,
            StorageFileUtils storageFileUtils,
            AgentSessionStorage sessionStorage) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.storageFileUtils = Objects.requireNonNull(storageFileUtils, "storageFileUtils");
        this.ownership = new AgentStorageOwnership(sessionStorage);
    }

    @Override
    public synchronized AgentEvent append(AgentEvent event, Long userId) {
        Objects.requireNonNull(event, "event");
        ownership.require(event.sessionId(), userId);
        Path directory = paths.resourceDirectory(event.sessionId(), "events");
        storageFileUtils.createPrivateDirectory(directory);
        storageFileUtils.verifyInsideRoot(paths.root(), directory);
        long expectedSequence = lastSequence(event.sessionId(), directory) + 1;
        if (event.sequence() != expectedSequence) {
            throw new StorageException(
                    "Agent event sequence must be " + expectedSequence + " but was " + event.sequence());
        }
        Path eventFile = paths.eventFile(event.sessionId(), event.sequence());
        storageFileUtils.rejectSymbolicLink(eventFile);
        if (Files.exists(eventFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("Agent event sequence already exists: " + event.sequence());
        }
        storageFileUtils.writeAtomically(eventFile, JSON.toJSONString(event));
        lastSequences.put(event.sessionId(), event.sequence());
        return event;
    }

    @Override
    public synchronized List<AgentEvent> list(
            String sessionId,
            Long userId,
            long afterSequence,
            int limit) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (!ownership.owns(sessionId, userId)) {
            return List.of();
        }
        Path directory = paths.resourceDirectory(sessionId, "events");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        storageFileUtils.rejectSymbolicLink(directory);
        storageFileUtils.verifyInsideRoot(paths.root(), directory);
        try (Stream<Path> entries = Files.list(directory)) {
            List<AgentEvent> stored = entries
                    .peek(storageFileUtils::rejectSymbolicLink)
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> readEvent(path, sessionId))
                    .toList();
            validateContinuousSequence(stored);
            lastSequences.put(sessionId, stored.isEmpty() ? 0L : stored.get(stored.size() - 1).sequence());
            return stored.stream()
                    .filter(event -> event.sequence() > afterSequence)
                    .limit(limit)
                    .toList();
        } catch (IOException exception) {
            throw new StorageException("Failed to list V2 agent events", exception);
        }
    }

    private long lastSequence(String sessionId, Path directory) {
        Long cached = lastSequences.get(sessionId);
        if (cached != null) {
            return cached;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            List<Long> sequences = entries
                    .peek(storageFileUtils::rejectSymbolicLink)
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .mapToLong(this::sequenceFromFile)
                    .sorted()
                    .boxed()
                    .toList();
            validateContinuousSequenceValues(sequences);
            long last = sequences.isEmpty() ? 0L : sequences.get(sequences.size() - 1);
            lastSequences.put(sessionId, last);
            return last;
        } catch (IOException exception) {
            throw new StorageException("Failed to inspect V2 agent events", exception);
        }
    }

    private void validateContinuousSequence(List<AgentEvent> events) {
        validateContinuousSequenceValues(events.stream().map(AgentEvent::sequence).toList());
    }

    private void validateContinuousSequenceValues(List<Long> sequences) {
        long expected = 1;
        for (Long sequence : sequences) {
            if (sequence == null || sequence != expected) {
                throw new StorageException("V2 agent event sequence is not continuous at " + expected);
            }
            expected++;
        }
    }

    private AgentEvent readEvent(Path eventFile, String sessionId) {
        storageFileUtils.rejectSymbolicLink(eventFile);
        storageFileUtils.verifyInsideRoot(paths.root(), eventFile);
        try {
            AgentEvent event = JSON.parseObject(
                    Files.readString(eventFile, StandardCharsets.UTF_8), AgentEvent.class);
            if (event == null || !Objects.equals(sessionId, event.sessionId())
                    || event.sequence() != sequenceFromFile(eventFile)) {
                throw new StorageException("Invalid V2 agent event record");
            }
            return event;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof StorageException storageException) {
                throw storageException;
            }
            throw new StorageException("Failed to read V2 agent event", exception);
        }
    }

    private long sequenceFromFile(Path eventFile) {
        String name = eventFile.getFileName().toString();
        if (!name.matches("[0-9]{20}\\.json")) {
            throw new StorageException("Invalid V2 agent event file: " + name);
        }
        try {
            return Long.parseLong(name.substring(0, 20));
        } catch (NumberFormatException exception) {
            throw new StorageException("Invalid V2 agent event sequence: " + name, exception);
        }
    }
}
