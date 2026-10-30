package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.service.task.ArtifactService;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Statement;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class TaskExecutionContextImpl implements TaskExecutionContext {

    private final Long taskId;

    private final RunningTask runningTask;

    private final TaskStorage taskStorage;

    private final ArtifactService artifactService;

    private final AtomicReference<String> stage = new AtomicReference<>();

    private final Map<Statement, TaskCancelable> activeStatements = new IdentityHashMap<>();

    private ArtifactDraft artifactDraft;

    /** Secondary artifact that lists the rows an import could not store; staged lazily. */
    private ArtifactDraft errorArtifactDraft;

    private BufferedWriter artifactWriter;

    private OutputStream artifactOutputStream;

    TaskExecutionContextImpl(Long taskId, RunningTask runningTask, TaskStorage taskStorage,
            ArtifactService artifactService) {
        this.taskId = taskId;
        this.runningTask = runningTask;
        this.taskStorage = taskStorage;
        this.artifactService = artifactService;
    }

    @Override
    public Long taskId() {
        return taskId;
    }

    @Override
    public void reportProgress(int progress, String stage, String message) {
        reportProgress(TaskProgress.builder()
                .progress(progress)
                .stage(stage)
                .message(message)
                .build());
    }

    @Override
    public void reportProgress(TaskProgress progress) {
        checkCancelled();
        if (progress == null || progress.getProgress() == null) {
            return;
        }
        this.stage.set(progress.getStage());
        taskStorage.updateProgressIfRunning(taskId, progress);
    }

    @Override
    public void logInfo(String code, String message) {
        logInfo(code, message, Collections.emptyMap());
    }

    @Override
    public void logInfo(String code, String message, Map<String, Object> details) {
        appendEvent(TaskEventLevel.INFO.name(), code, message, details);
    }

    @Override
    public void logWarn(String code, String message, Map<String, Object> details) {
        appendEvent(TaskEventLevel.WARN.name(), code, message, details);
    }

    @Override
    public void logError(String code, String message, Map<String, Object> details) {
        appendEvent(TaskEventLevel.ERROR.name(), code, message, details);
    }

    @Override
    public void checkCancelled() {
        if (runningTask.cancellationToken().isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException();
        }
    }

    @Override
    public void registerCancelable(TaskCancelable resource) {
        runningTask.registerCancelable(resource);
    }

    @Override
    public void cancelResources() {
        runningTask.cancelResources();
    }

    @Override
    public synchronized ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
        checkCancelled();
        if (artifactDraft != null) {
            throw new IllegalStateException("A task can create at most one artifact");
        }
        ArtifactDraft draft = artifactService.createDraft(taskId, outputDirectory, fileName, mediaType);
        try {
            appendEvent(TaskEventLevel.INFO.name(), TaskEventCode.ARTIFACT_PREPARED.name(),
                    "Artifact prepared", Map.of(
                            TaskConstants.ARTIFACT_TEMPORARY_PATH_DETAIL_KEY,
                            draft.getTemporaryFile().getAbsolutePath(),
                            TaskConstants.ARTIFACT_TARGET_PATH_DETAIL_KEY,
                            draft.getTargetFile().getAbsolutePath()));
            artifactDraft = draft;
            return draft;
        } catch (RuntimeException e) {
            artifactService.deleteDraft(draft);
            throw e;
        }
    }

    @Override
    public synchronized ArtifactDraft createErrorArtifact(String outputDirectory, String fileName, String mediaType) {
        checkCancelled();
        if (errorArtifactDraft != null) {
            return errorArtifactDraft;
        }
        ArtifactDraft draft = artifactService.createDraft(taskId, outputDirectory, fileName, mediaType);
        try {
            // Deliberately no ARTIFACT_PREPARED event: that code marks the temporary path of the
            // primary artifact for the interrupted-artifact cleanup, and a rejected-rows file must
            // survive both the task and the application that produced it.
            errorArtifactDraft = draft;
            return draft;
        } catch (RuntimeException e) {
            artifactService.deleteDraft(draft);
            throw e;
        }
    }

    @Override
    public synchronized ArtifactDraft errorArtifactDraft() {
        return errorArtifactDraft;
    }

    @Override
    public synchronized void saveCheckpoint(String checkpointJson) {
        if (taskId == null || StringUtils.isBlank(checkpointJson)) {
            return;
        }
        taskStorage.saveCheckpoint(taskId, checkpointJson);
    }

    @Override
    public synchronized void write(String content) {
        checkCancelled();
        if (artifactDraft == null) {
            throw new IllegalStateException("Create an artifact before writing content");
        }
        try {
            openArtifactWriter();
            artifactWriter.write(content);
            artifactWriter.newLine();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write task artifact", e);
        }
    }

    @Override
    public synchronized void writeBytes(byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        checkCancelled();
        if (artifactDraft == null) {
            throw new IllegalStateException("Create an artifact before writing content");
        }
        try {
            openArtifactWriter();
            // Flush pending characters first so text and raw bytes keep their relative order.
            artifactWriter.flush();
            artifactOutputStream.write(buffer, offset, length);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write task artifact", e);
        }
    }

    private void openArtifactWriter() throws IOException {
        if (artifactWriter != null) {
            return;
        }
        artifactOutputStream = Files.newOutputStream(artifactDraft.getTemporaryFile().toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        artifactWriter = new BufferedWriter(new OutputStreamWriter(artifactOutputStream, StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void onStatementCreated(Statement statement) {
        if (statement == null || activeStatements.containsKey(statement)) {
            return;
        }
        TaskCancelable cancelable = statement::cancel;
        activeStatements.put(statement, cancelable);
        runningTask.registerCancelable(cancelable);
    }

    @Override
    public synchronized void onStatementClosed(Statement statement) {
        TaskCancelable cancelable = activeStatements.remove(statement);
        if (cancelable != null) {
            runningTask.clearCancelable(cancelable);
        }
    }

    synchronized void finishArtifactWrites() {
        if (artifactWriter == null) {
            return;
        }
        try {
            artifactWriter.flush();
            artifactWriter.close();
            artifactWriter = null;
            artifactOutputStream = null;
        } catch (IOException e) {
            throw new IllegalStateException("Could not close task artifact", e);
        }
    }

    synchronized void closeQuietly() {
        if (artifactWriter == null) {
            return;
        }
        try {
            artifactWriter.close();
        } catch (IOException ignored) {
            // The task result has already been decided.
        } finally {
            artifactWriter = null;
            artifactOutputStream = null;
        }
    }

    ArtifactDraft artifactDraft() {
        return artifactDraft;
    }

    private void appendEvent(String level, String code, String message, Map<String, Object> details) {
        checkCancelled();
        taskStorage.appendEvent(TaskEvent.builder()
                .taskId(taskId)
                .level(level)
                .code(code)
                .stage(stage.get())
                .message(message)
                .details(details == null ? Collections.emptyMap() : Map.copyOf(details))
                .build());
    }

}
