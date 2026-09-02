package ai.chat2db.community.storage.large;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.storage.IdUtil;
import ai.chat2db.community.storage.TaskLifecyclePolicy;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
public class FileTaskStorage implements TaskStorage {

    public static final String TASK_STORAGE_DIRECTORY = "task-v2";

    public static final String TASK_INDEX_NAME = "task";

    public static final String TASK_EVENT_FILE_SUFFIX = "-events.json";

    static final String TASK_TRANSITION_FILE_SUFFIX = "-transition.json";

    static final String DELETING_FILE_SUFFIX = ".deleting";

    static final int NO_FILE_LIMIT = 0;

    private final TaskSnapshotStorage snapshots;

    // Only one sequence number per task is cached; event bodies remain on disk and are paged on demand.
    private final Map<Long, Long> lastEventSequences = new ConcurrentHashMap<>();

    public FileTaskStorage() {
        this(new TaskSnapshotStorage());
    }

    public FileTaskStorage(String storageBasePath) {
        this(new TaskSnapshotStorage(storageBasePath));
    }

    private FileTaskStorage(TaskSnapshotStorage snapshots) {
        this.snapshots = snapshots;
        recoverStagedEventDeletions();
        recoverTransitions();
    }

    @Override
    public synchronized Task create(Task task, TaskEvent createdEvent) {
        if (task == null || createdEvent == null) {
            throw new IllegalArgumentException("task and createdEvent are required");
        }
        Task stored = copy(task);
        Long taskId;
        do {
            taskId = IdUtil.generateId();
        } while (snapshots.find(taskId) != null);
        Date now = new Date();
        stored.setId(taskId);
        stored.setStatus(TaskStatus.PENDING.name());
        stored.setProgress(TaskConstants.PENDING_PROGRESS);
        stored.setCreatedAt(now);
        stored.setUpdatedAt(now);

        TaskEvent event = prepareEvent(taskId, createdEvent);
        TaskTransition transition = new TaskTransition(stored, event);
        writeTransition(transition);
        commitTransition(transition, false);
        copyInto(stored, task);
        return copy(stored);
    }

    @Override
    public synchronized Optional<Task> get(Long taskId) {
        return Optional.ofNullable(snapshots.find(taskId)).map(this::copy);
    }

    @Override
    public synchronized PageResponse<Task> list(TaskQuery query) {
        TaskQuery effectiveQuery = query == null ? new TaskQuery() : query;
        int pageNo = Math.max(1, effectiveQuery.getPageNo() == null ? 1 : effectiveQuery.getPageNo());
        int pageSize = Math.max(1, effectiveQuery.getPageSize() == null
                ? TaskConstants.DEFAULT_PAGE_SIZE : effectiveQuery.getPageSize());
        long offset = (long) (pageNo - 1) * pageSize;
        long total = 0L;
        List<Task> page = new ArrayList<>(pageSize);
        for (Task task : snapshots.newestFirst()) {
            if ((effectiveQuery.getStatus() != null
                    && !Objects.equals(task.getStatus(), effectiveQuery.getStatus()))
                    || !Objects.equals(task.getUserId(), effectiveQuery.getUserId())
                    || !Objects.equals(task.getOrganizationId(), effectiveQuery.getOrganizationId())) {
                continue;
            }
            if (total >= offset && page.size() < pageSize) {
                page.add(copy(task));
            }
            total++;
        }
        return PageResponse.of(List.copyOf(page), total, pageNo, pageSize);
    }

    @Override
    public synchronized boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
            TaskStatusPatch patch, TaskEvent lifecycleEvent) {
        Task current = snapshots.find(taskId);
        if (current == null || !expectedStatus.equals(current.getStatus())
                || !TaskLifecyclePolicy.isLegalTransition(expectedStatus, targetStatus)) {
            return false;
        }
        if (lifecycleEvent == null) {
            throw new IllegalArgumentException("A status transition requires a lifecycle event");
        }

        Task updated = copy(current);
        TaskLifecyclePolicy.applyStatusPatch(updated, targetStatus, patch);
        TaskTransition transition = new TaskTransition(updated, prepareEvent(taskId, lifecycleEvent));
        writeTransition(transition);
        commitTransition(transition, false);
        return true;
    }

    @Override
    public synchronized boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
        Task currentTask = snapshots.find(taskId);
        if (currentTask == null || !TaskStatus.RUNNING.name().equals(currentTask.getStatus())
                || progress == null || progress.getProgress() == null) {
            return false;
        }
        int requested = TaskLifecyclePolicy.runningProgress(progress.getProgress());
        int current = currentTask.getProgress() == null ? TaskConstants.PENDING_PROGRESS : currentTask.getProgress();
        if (requested < current) {
            return false;
        }
        Task updated = copy(currentTask);
        updated.setProgress(requested);
        updated.setStage(progress.getStage());
        updated.setProgressMessage(progress.getMessage());
        updated.setUpdatedAt(new Date());
        snapshots.replaceStrict(taskId, updated);
        return true;
    }

    @Override
    public synchronized TaskEvent appendEvent(TaskEvent event) {
        if (event == null || event.getTaskId() == null || snapshots.find(event.getTaskId()) == null) {
            throw new IllegalArgumentException("event must reference an existing task");
        }
        TaskEvent prepared = prepareEvent(event.getTaskId(), event);
        appendEventLine(prepared);
        return copyEvent(prepared);
    }

    @Override
    public synchronized List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
        int resultLimit = TaskLifecyclePolicy.eventLimit(limit);
        File file = eventsFile(taskId);
        repairIncompleteTrailingEvent(file, taskId);
        if (!file.isFile()) {
            return List.of();
        }
        if (afterSequence > 0L && afterSequence >= lastSequence(taskId)) {
            return List.of();
        }
        List<TaskEvent> result = new ArrayList<>(resultLimit);
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long startOffset = afterSequence <= 0L ? 0L : findOffsetAfterSequence(input, taskId, afterSequence);
            input.seek(startOffset);
            String line;
            while ((line = readNextLine(input)) != null && result.size() < resultLimit) {
                TaskEvent event = parseValidEvent(line, taskId);
                if (event != null && event.getSequence() > afterSequence) {
                    result.add(copyEvent(event));
                }
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read task events", e);
        }
    }

    private long findOffsetAfterSequence(RandomAccessFile input, Long taskId, long afterSequence) throws IOException {
        long position = skipTrailingLineBreaks(input, input.length() - 1L);
        while (position >= 0L) {
            long lineEnd = position;
            PreviousLine previousLine = readPreviousLine(input, position);
            TaskEvent event = parseValidEvent(previousLine.value(), taskId);
            if (event != null && event.getSequence() <= afterSequence) {
                return skipLineBreaksForward(input, lineEnd + 1L);
            }
            position = previousLine.nextPosition();
        }
        return 0L;
    }

    private String readNextLine(RandomAccessFile input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean readAny = false;
        int value;
        while ((value = input.read()) != -1) {
            readAny = true;
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.write(value);
            }
        }
        return readAny ? line.toString(StandardCharsets.UTF_8) : null;
    }

    @Override
    public synchronized List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
        int resultLimit = TaskLifecyclePolicy.eventLimit(limit);
        File file = eventsFile(taskId);
        repairIncompleteTrailingEvent(file, taskId);
        if (!file.isFile()) {
            return List.of();
        }
        List<TaskEvent> newestFirst = new ArrayList<>(resultLimit);
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long position = skipTrailingLineBreaks(input, input.length() - 1L);
            Long latestSequence = null;
            while (position >= 0 && newestFirst.size() < resultLimit) {
                PreviousLine previousLine = readPreviousLine(input, position);
                position = previousLine.nextPosition();
                TaskEvent event = parseValidEvent(previousLine.value(), taskId);
                if (event != null && latestSequence == null) {
                    latestSequence = event.getSequence();
                }
                if (event != null && (beforeSequence == null || event.getSequence() < beforeSequence)) {
                    newestFirst.add(copyEvent(event));
                }
            }
            if (latestSequence != null) {
                lastEventSequences.merge(taskId, latestSequence, Math::max);
            }
            Collections.reverse(newestFirst);
            return List.copyOf(newestFirst);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read task events", e);
        }
    }

    private long skipTrailingLineBreaks(RandomAccessFile input, long position) throws IOException {
        while (position >= 0) {
            input.seek(position);
            int value = input.read();
            if (value != '\n' && value != '\r') {
                break;
            }
            position--;
        }
        return position;
    }

    private long skipLineBreaksForward(RandomAccessFile input, long position) throws IOException {
        long length = input.length();
        while (position < length) {
            input.seek(position);
            int value = input.read();
            if (value != '\n' && value != '\r') {
                break;
            }
            position++;
        }
        return position;
    }

    private PreviousLine readPreviousLine(RandomAccessFile input, long position) throws IOException {
        ByteArrayOutputStream reversed = new ByteArrayOutputStream();
        while (position >= 0) {
            input.seek(position--);
            int value = input.read();
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                reversed.write(value);
            }
        }
        byte[] bytes = reversed.toByteArray();
        for (int left = 0, right = bytes.length - 1; left < right; left++, right--) {
            byte value = bytes[left];
            bytes[left] = bytes[right];
            bytes[right] = value;
        }
        return new PreviousLine(new String(bytes, StandardCharsets.UTF_8), position);
    }

    @Override
    public synchronized List<Task> listNonTerminalTasks() {
        return snapshots.all().stream()
                .filter(task -> task.getStatus() != null && !TaskStatus.isTerminal(task.getStatus()))
                .map(this::copy)
                .toList();
    }

    @Override
    public synchronized List<Task> listTasksForRecovery() {
        return snapshots.all().stream().map(this::copy).toList();
    }

    @Override
    public synchronized boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
        Task task = snapshots.find(taskId);
        if (task == null || !TaskStatus.isTerminal(task.getStatus())) {
            return false;
        }

        Path eventFile = eventsFile(taskId).toPath();
        Path stagedEventFile = stagedEventsFile(taskId);
        boolean eventStaged = false;
        try {
            Files.deleteIfExists(transitionFile(taskId));
            Files.deleteIfExists(stagedEventFile);
            if (Files.exists(eventFile)) {
                move(eventFile, stagedEventFile);
                eventStaged = true;
            }
            snapshots.removeStrict(taskId);
            if (commitAction != null) {
                commitAction.run();
            }
            try {
                Files.deleteIfExists(stagedEventFile);
            } catch (IOException cleanupFailure) {
                log.warn("Could not delete staged events for deleted task {}", taskId, cleanupFailure);
            }
            lastEventSequences.remove(taskId);
            return true;
        } catch (Exception e) {
            if (snapshots.find(taskId) == null) {
                try {
                    snapshots.restoreStrict(task);
                } catch (RuntimeException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            if (eventStaged && Files.exists(stagedEventFile) && !Files.exists(eventFile)) {
                try {
                    move(stagedEventFile, eventFile);
                } catch (IOException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            throw e instanceof RuntimeException runtimeException
                    ? runtimeException : new RuntimeException(e);
        }
    }

    @Override
    public synchronized List<TaskArtifact> listArtifacts(Long taskId) {
        Task task = taskId == null ? null : snapshots.find(taskId);
        return task == null || task.getArtifacts() == null ? List.of() : List.copyOf(task.getArtifacts());
    }

    @Override
    public synchronized void saveArtifact(Long taskId, TaskArtifact artifact) {
        if (taskId == null || artifact == null || artifact.getArtifactId() == null || artifact.getRole() == null) {
            throw new IllegalArgumentException("artifact must reference an existing task");
        }
        mutateTask(taskId, "artifact", updated -> {
            List<TaskArtifact> artifacts = new ArrayList<>(
                    updated.getArtifacts() == null ? List.of() : updated.getArtifacts());
            artifacts.removeIf(existing -> artifact.getArtifactId().equals(existing.getArtifactId()));
            artifacts.add(JSON.parseObject(JSON.toJSONString(artifact), TaskArtifact.class));
            updated.setArtifacts(artifacts);
        });
    }

    @Override
    public synchronized void deleteArtifact(Long taskId, String artifactId) {
        if (taskId == null || artifactId == null || snapshots.find(taskId) == null) {
            return;
        }
        mutateTask(taskId, "artifact", updated -> {
            List<TaskArtifact> artifacts = new ArrayList<>(
                    updated.getArtifacts() == null ? List.of() : updated.getArtifacts());
            artifacts.removeIf(existing -> artifactId.equals(existing.getArtifactId()));
            updated.setArtifacts(artifacts);
        });
    }

    @Override
    public synchronized List<Task> listResumableTasks() {
        return snapshots.all().stream()
                .filter(task -> task.getStatus() != null && !TaskStatus.isTerminal(task.getStatus()))
                .filter(task -> task.getResumeStates() != null && !task.getResumeStates().isEmpty())
                .map(this::copy)
                .toList();
    }

    @Override
    public synchronized void saveResumeState(Long taskId, ResumeState state) {
        if (taskId == null || state == null || state.getShardNo() == null || state.getKind() == null) {
            throw new IllegalArgumentException("resume state must reference an existing task");
        }
        mutateTask(taskId, "resume state", updated -> {
            List<ResumeState> states = new ArrayList<>(
                    updated.getResumeStates() == null ? List.of() : updated.getResumeStates());
            states.removeIf(existing -> state.getShardNo().equals(existing.getShardNo()));
            states.add(JSON.parseObject(JSON.toJSONString(state), ResumeState.class));
            states.sort(Comparator.comparing(ResumeState::getShardNo));
            updated.setResumeStates(states);
        });
    }

    @Override
    public synchronized List<ResumeState> listResumeStates(Long taskId) {
        Task task = taskId == null ? null : snapshots.find(taskId);
        return task == null || task.getResumeStates() == null ? List.of() : List.copyOf(task.getResumeStates());
    }

    @Override
    public synchronized void clearResumeStates(Long taskId) {
        if (taskId == null || snapshots.find(taskId) == null) {
            return;
        }
        mutateTask(taskId, "resume state", updated -> updated.setResumeStates(null));
    }

    /**
     * Rewrites the stored snapshot with {@code change} applied to a copy, so artifact and resume
     * data share the task snapshot's single-writer semantics and its deletion rollback.
     */
    private void mutateTask(Long taskId, String subject, Consumer<Task> change) {
        Task current = snapshots.find(taskId);
        if (current == null) {
            throw new IllegalArgumentException(subject + " must reference an existing task");
        }
        Task updated = copy(current);
        change.accept(updated);
        snapshots.replaceStrict(taskId, updated);
    }

    private void commitTransition(TaskTransition transition, boolean recovery) {
        TaskEvent event = transition.getEvent();
        long previousLength = eventFileLength(event.getTaskId());
        boolean appended = false;
        try {
            appended = ensureEvent(event);
            snapshots.upsertStrict(transition.getTask());
        } catch (RuntimeException e) {
            if (!recovery && appended) {
                try {
                    truncateEventFile(event.getTaskId(), previousLength, event.getSequence() - 1L);
                    deleteTransitionFile(event.getTaskId());
                } catch (RuntimeException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            throw e;
        }
        deleteTransitionFile(event.getTaskId());
    }

    private boolean ensureEvent(TaskEvent event) {
        TaskEvent existing = findEvent(event.getTaskId(), event.getSequence());
        if (existing != null) {
            if (!Objects.equals(existing.getEventId(), event.getEventId())) {
                throw new IllegalStateException("Task event sequence is already occupied");
            }
            lastEventSequences.put(event.getTaskId(), event.getSequence());
            return false;
        }
        long lastSequence = lastSequence(event.getTaskId());
        if (event.getSequence() != lastSequence + 1L) {
            throw new IllegalStateException("Task event sequence is not contiguous");
        }
        appendEventLine(event);
        return true;
    }

    private TaskEvent prepareEvent(Long taskId, TaskEvent source) {
        TaskEvent event = copyEvent(source);
        event.setTaskId(taskId);
        event.setEventId(event.getEventId() == null ? IdUtil.generateId() : event.getEventId());
        event.setSequence(lastSequence(taskId) + 1L);
        event.setCreatedAt(event.getCreatedAt() == null ? new Date() : event.getCreatedAt());
        return event;
    }

    private void appendEventLine(TaskEvent event) {
        Path file = eventsFile(event.getTaskId()).toPath();
        byte[] bytes = (JSON.toJSONString(event) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            lastEventSequences.put(event.getTaskId(), event.getSequence());
        } catch (IOException e) {
            throw new IllegalStateException("Could not append task event", e);
        }
    }

    private TaskEvent findEvent(Long taskId, Long sequence) {
        if (sequence == null) {
            return null;
        }
        File file = eventsFile(taskId);
        repairIncompleteTrailingEvent(file, taskId);
        if (!file.isFile()) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                TaskEvent event = parseValidEvent(line, taskId);
                if (event != null && Objects.equals(event.getSequence(), sequence)) {
                    return event;
                }
            }
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("Could not inspect task events", e);
        }
    }

    private long lastSequence(Long taskId) {
        return lastEventSequences.computeIfAbsent(taskId, this::findLastSequence);
    }

    private long findLastSequence(Long taskId) {
        File file = eventsFile(taskId);
        repairIncompleteTrailingEvent(file, taskId);
        if (!file.isFile()) {
            return 0L;
        }
        long lastSequence = 0L;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                TaskEvent event = parseValidEvent(line, taskId);
                if (event != null) {
                    lastSequence = Math.max(lastSequence, event.getSequence());
                }
            }
            return lastSequence;
        } catch (IOException e) {
            throw new IllegalStateException("Could not inspect task event sequence", e);
        }
    }

    private TaskEvent parseValidEvent(String line, Long taskId) {
        if (StringUtils.isBlank(line)) {
            return null;
        }
        try {
            TaskEvent event = JSON.parseObject(line, TaskEvent.class);
            if (event == null || event.getSequence() == null || !taskId.equals(event.getTaskId())) {
                log.warn("Skipping invalid task event for task {}", taskId);
                return null;
            }
            return event;
        } catch (RuntimeException e) {
            log.warn("Skipping unreadable task event for task {}", taskId);
            return null;
        }
    }

    private void repairIncompleteTrailingEvent(File file, Long taskId) {
        if (!file.isFile()) {
            return;
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "rw")) {
            long length = input.length();
            if (length == 0) {
                return;
            }
            input.seek(length - 1);
            int lastByte = input.read();
            if (lastByte == '\n' || lastByte == '\r') {
                return;
            }
            long lineStart = length - 1;
            while (lineStart > 0) {
                input.seek(lineStart - 1);
                int current = input.read();
                if (current == '\n' || current == '\r') {
                    break;
                }
                lineStart--;
            }
            byte[] trailing = new byte[(int) (length - lineStart)];
            input.seek(lineStart);
            input.readFully(trailing);
            TaskEvent event = parseValidEvent(new String(trailing, StandardCharsets.UTF_8), taskId);
            input.seek(length);
            if (event == null) {
                input.setLength(lineStart);
            } else {
                input.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
            }
            lastEventSequences.remove(taskId);
        } catch (IOException e) {
            throw new IllegalStateException("Could not repair trailing task event", e);
        }
    }

    private void recoverTransitions() {
        File[] journals = snapshots.storageDirectory().listFiles(
                file -> file.isFile() && file.getName().endsWith(TASK_TRANSITION_FILE_SUFFIX));
        if (journals == null) {
            return;
        }
        for (File journalFile : journals) {
            try {
                TaskTransition transition = JSON.parseObject(FileUtil.readUtf8String(journalFile),
                        TaskTransition.class);
                if (transition == null || transition.getTask() == null || transition.getEvent() == null) {
                    throw new IllegalStateException("Task transition journal is incomplete");
                }
                commitTransition(transition, true);
            } catch (RuntimeException e) {
                log.error("Could not recover task transition from {}", journalFile, e);
            }
        }
    }

    private void recoverStagedEventDeletions() {
        File[] stagedFiles = snapshots.storageDirectory().listFiles(
                file -> file.getName().endsWith(TASK_EVENT_FILE_SUFFIX + DELETING_FILE_SUFFIX));
        if (stagedFiles == null) {
            return;
        }
        for (File stagedFile : stagedFiles) {
            String taskIdValue = StringUtils.substringBefore(stagedFile.getName(), TASK_EVENT_FILE_SUFFIX);
            try {
                Long taskId = Long.valueOf(taskIdValue);
                Path original = eventsFile(taskId).toPath();
                if (snapshots.find(taskId) == null) {
                    Files.deleteIfExists(stagedFile.toPath());
                } else if (!Files.exists(original)) {
                    move(stagedFile.toPath(), original);
                } else {
                    Files.deleteIfExists(stagedFile.toPath());
                }
            } catch (Exception e) {
                log.error("Could not recover staged task event deletion from {}", stagedFile, e);
            }
        }
    }

    private void writeTransition(TaskTransition transition) {
        Long taskId = transition.getTask().getId();
        try {
            LargeDataStorage.writeUtf8Atomically(transitionFile(taskId), JSON.toJSONString(transition));
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist task transition", e);
        }
    }

    private void deleteTransitionFile(Long taskId) {
        try {
            Files.deleteIfExists(transitionFile(taskId));
        } catch (IOException e) {
            log.warn("Could not delete committed task transition for task {}", taskId, e);
        }
    }

    private long eventFileLength(Long taskId) {
        File file = eventsFile(taskId);
        return file.isFile() ? file.length() : 0L;
    }

    private void truncateEventFile(Long taskId, long length, long lastSequence) {
        File file = eventsFile(taskId);
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(length);
            lastEventSequences.put(taskId, lastSequence);
        } catch (IOException e) {
            throw new IllegalStateException("Could not roll back task event append", e);
        }
    }

    private File eventsFile(Long taskId) {
        return new File(snapshots.storageDirectory(), taskId + TASK_EVENT_FILE_SUFFIX);
    }

    private Path stagedEventsFile(Long taskId) {
        return eventsFile(taskId).toPath().resolveSibling(
                eventsFile(taskId).getName() + DELETING_FILE_SUFFIX);
    }

    private Path transitionFile(Long taskId) {
        return new File(snapshots.storageDirectory(), taskId + TASK_TRANSITION_FILE_SUFFIX).toPath();
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private Task copy(Task task) {
        return JSON.parseObject(JSON.toJSONString(task), Task.class);
    }

    private TaskEvent copyEvent(TaskEvent event) {
        return JSON.parseObject(JSON.toJSONString(event), TaskEvent.class);
    }

    private void copyInto(Task source, Task target) {
        Task copy = copy(source);
        target.setId(copy.getId());
        target.setType(copy.getType());
        target.setName(copy.getName());
        target.setStatus(copy.getStatus());
        target.setProgress(copy.getProgress());
        target.setStage(copy.getStage());
        target.setProgressMessage(copy.getProgressMessage());
        target.setTarget(copy.getTarget());
        target.setSpecJson(copy.getSpecJson());
        target.setErrorCode(copy.getErrorCode());
        target.setErrorMessage(copy.getErrorMessage());
        target.setArtifactId(copy.getArtifactId());
        target.setUserId(copy.getUserId());
        target.setOrganizationId(copy.getOrganizationId());
        target.setCreatedAt(copy.getCreatedAt());
        target.setStartedAt(copy.getStartedAt());
        target.setFinishedAt(copy.getFinishedAt());
        target.setUpdatedAt(copy.getUpdatedAt());
    }

    private record PreviousLine(String value, long nextPosition) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static final class TaskTransition {
        private Task task;
        private TaskEvent event;
    }

    private static final class TaskSnapshotStorage extends LargeDataStorage<Task> {

        private TaskSnapshotStorage() {
            super(TASK_STORAGE_DIRECTORY, TASK_INDEX_NAME, Task.class, NO_FILE_LIMIT);
        }

        private TaskSnapshotStorage(String storageBasePath) {
            super(TASK_STORAGE_DIRECTORY, TASK_INDEX_NAME, Task.class, NO_FILE_LIMIT, storageBasePath);
        }

        private Task find(Long taskId) {
            return getById(taskId);
        }

        private List<Task> all() {
            return getDataList();
        }

        private Iterable<Task> newestFirst() {
            return dataMap.descendingMap().values();
        }

        private void replaceStrict(Long taskId, Task task) {
            if (find(taskId) == null) {
                throw new IllegalStateException("Task does not exist");
            }
            replaceData(taskId, task);
        }

        private void upsertStrict(Task task) {
            upsertDataStrict(task.getId(), task);
        }

        private void removeStrict(Long taskId) {
            if (removeDataStrict(taskId) == null) {
                throw new IllegalStateException("Task does not exist");
            }
        }

        private void restoreStrict(Task task) {
            upsertDataStrict(task.getId(), task);
        }

        private File storageDirectory() {
            return new File(storageDir);
        }
    }
}
