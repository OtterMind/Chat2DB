package ai.chat2db.community.storage.large;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.storage.IdUtil;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

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

@Slf4j
@Component
public class FileTaskStorage implements TaskStorage {

    static final String TASK_STORAGE_DIRECTORY = "task-v2";

    static final String TASK_INDEX_NAME = "task";

    static final String TASK_EVENT_FILE_SUFFIX = "-events.json";

    static final String TASK_TRANSITION_FILE_SUFFIX = "-transition.json";

    static final String DELETING_FILE_SUFFIX = ".deleting";

    static final int NO_FILE_LIMIT = 0;

    private final TaskSnapshotStorage snapshots;

    // Only one sequence number per task is cached; event bodies remain on disk and are paged on demand.
    private final Map<Long, Long> lastEventSequences = new ConcurrentHashMap<>();

    public FileTaskStorage() {
        this(new TaskSnapshotStorage());
    }

    FileTaskStorage(String storageBasePath) {
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
        Comparator<Task> newestFirst = Comparator
                .comparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Task::getId, Comparator.nullsLast(Comparator.reverseOrder()));
        List<Task> tasks = snapshots.all().stream()
                .filter(task -> effectiveQuery.getStatus() == null
                        || Objects.equals(task.getStatus(), effectiveQuery.getStatus()))
                .filter(task -> Objects.equals(task.getUserId(), effectiveQuery.getUserId()))
                .filter(task -> Objects.equals(task.getOrganizationId(), effectiveQuery.getOrganizationId()))
                .sorted(newestFirst)
                .map(this::copy)
                .toList();
        int pageNo = Math.max(1, effectiveQuery.getPageNo() == null ? 1 : effectiveQuery.getPageNo());
        int pageSize = Math.max(1, effectiveQuery.getPageSize() == null
                ? TaskConstants.DEFAULT_PAGE_SIZE : effectiveQuery.getPageSize());
        long total = tasks.size();
        long offset = (long) (pageNo - 1) * pageSize;
        if (offset >= total) {
            return PageResponse.of(List.of(), total, pageNo, pageSize);
        }
        int from = (int) offset;
        int to = (int) Math.min(total, offset + pageSize);
        return PageResponse.of(tasks.subList(from, to), total, pageNo, pageSize);
    }

    @Override
    public synchronized boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
            TaskStatusPatch patch, TaskEvent lifecycleEvent) {
        Task current = snapshots.find(taskId);
        if (current == null || !expectedStatus.equals(current.getStatus())
                || !isLegalTransition(expectedStatus, targetStatus)) {
            return false;
        }
        if (lifecycleEvent == null) {
            throw new IllegalArgumentException("A status transition requires a lifecycle event");
        }

        Task updated = copy(current);
        applyPatch(updated, targetStatus, patch);
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
        int requested = Math.max(TaskConstants.STARTED_PROGRESS,
                Math.min(TaskConstants.MAX_RUNNING_PROGRESS, progress.getProgress()));
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
        int resultLimit = eventLimit(limit);
        File file = eventsFile(taskId);
        repairIncompleteTrailingEvent(file, taskId);
        if (!file.isFile()) {
            return List.of();
        }
        List<TaskEvent> result = new ArrayList<>(resultLimit);
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && result.size() < resultLimit) {
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

    @Override
    public synchronized List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
        int resultLimit = eventLimit(limit);
        File file = eventsFile(taskId);
        repairIncompleteTrailingEvent(file, taskId);
        if (!file.isFile()) {
            return List.of();
        }
        List<TaskEvent> newestFirst = new ArrayList<>(resultLimit);
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long position = skipTrailingLineBreaks(input, input.length() - 1L);
            while (position >= 0 && newestFirst.size() < resultLimit) {
                PreviousLine previousLine = readPreviousLine(input, position);
                position = previousLine.nextPosition();
                TaskEvent event = parseValidEvent(previousLine.value(), taskId);
                if (event != null && (beforeSequence == null || event.getSequence() < beforeSequence)) {
                    newestFirst.add(copyEvent(event));
                }
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
    public synchronized boolean deleteTerminalTask(Long taskId) {
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
            try {
                Files.deleteIfExists(stagedEventFile);
            } catch (IOException cleanupFailure) {
                snapshots.restoreStrict(task);
                if (eventStaged) {
                    move(stagedEventFile, eventFile);
                }
                throw cleanupFailure;
            }
            lastEventSequences.remove(taskId);
            return true;
        } catch (Exception e) {
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

    private int eventLimit(int limit) {
        return Math.max(1, Math.min(TaskConstants.MAX_EVENT_LIMIT, limit));
    }

    private void applyPatch(Task task, String targetStatus, TaskStatusPatch patch) {
        TaskStatusPatch effectivePatch = patch == null ? new TaskStatusPatch() : patch;
        int previousProgress = task.getProgress() == null ? TaskConstants.PENDING_PROGRESS : task.getProgress();
        task.setStatus(targetStatus);
        if (TaskStatus.SUCCESS.name().equals(targetStatus)) {
            task.setProgress(TaskConstants.COMPLETED_PROGRESS);
        } else if (!TaskStatus.isTerminal(targetStatus) && effectivePatch.getProgress() != null) {
            task.setProgress(Math.max(previousProgress, Math.min(TaskConstants.MAX_RUNNING_PROGRESS,
                    effectivePatch.getProgress())));
        } else {
            task.setProgress(previousProgress);
        }
        if (effectivePatch.getStage() != null) {
            task.setStage(effectivePatch.getStage());
        }
        task.setProgressMessage(effectivePatch.getProgressMessage());
        task.setErrorCode(TaskStatus.FAILED.name().equals(targetStatus) ? effectivePatch.getErrorCode() : null);
        task.setErrorMessage(TaskStatus.FAILED.name().equals(targetStatus) ? effectivePatch.getErrorMessage() : null);
        task.setArtifactId(TaskStatus.SUCCESS.name().equals(targetStatus) ? effectivePatch.getArtifactId() : null);
        if (effectivePatch.getStartedAt() != null) {
            task.setStartedAt(effectivePatch.getStartedAt());
        }
        if (effectivePatch.getFinishedAt() != null) {
            task.setFinishedAt(effectivePatch.getFinishedAt());
        }
        task.setUpdatedAt(effectivePatch.getUpdatedAt() == null ? new Date() : effectivePatch.getUpdatedAt());
    }

    private boolean isLegalTransition(String source, String target) {
        if (TaskStatus.PENDING.name().equals(source)) {
            return TaskStatus.RUNNING.name().equals(target) || TaskStatus.CANCELLED.name().equals(target)
                    || TaskStatus.FAILED.name().equals(target);
        }
        if (TaskStatus.RUNNING.name().equals(source)) {
            return TaskStatus.SUCCESS.name().equals(target) || TaskStatus.FAILED.name().equals(target)
                    || TaskStatus.CANCELLED.name().equals(target);
        }
        return false;
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
