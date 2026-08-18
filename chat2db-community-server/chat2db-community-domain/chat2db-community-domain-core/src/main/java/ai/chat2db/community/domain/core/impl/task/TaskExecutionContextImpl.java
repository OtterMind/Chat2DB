package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class TaskExecutionContextImpl implements TaskExecutionContext {

    private final Long taskId;

    private final RunningTask runningTask;

    private final TaskStorage taskStorage;

    private final ArtifactService artifactService;

    private final AtomicReference<String> stage = new AtomicReference<>();

    private final AtomicReference<StatementRegistration> activeStatement = new AtomicReference<>();

    private ArtifactDraft artifactDraft;

    private BufferedWriter artifactWriter;

    TaskExecutionContextImpl(Long taskId, RunningTask runningTask, TaskStorage taskStorage,
            ArtifactService artifactService) {
        this.taskId = taskId;
        this.runningTask = runningTask;
        this.taskStorage = taskStorage;
        this.artifactService = artifactService;
    }

    @Override
    public void reportProgress(int progress, String stage, String message) {
        checkCancelled();
        this.stage.set(stage);
        taskStorage.updateProgressIfRunning(taskId, TaskProgress.builder()
                .progress(progress)
                .stage(stage)
                .message(message)
                .build());
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
        activeStatement.set(null);
        runningTask.registerCancelable(resource);
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
    public synchronized void write(String content) {
        checkCancelled();
        if (artifactDraft == null) {
            throw new IllegalStateException("Create an artifact before writing content");
        }
        try {
            if (artifactWriter == null) {
                artifactWriter = Files.newBufferedWriter(artifactDraft.getTemporaryFile().toPath(),
                        StandardCharsets.UTF_8);
            }
            artifactWriter.write(content);
            artifactWriter.newLine();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write task artifact", e);
        }
    }

    @Override
    public void onStatementCreated(Statement statement) {
        if (statement == null) {
            return;
        }
        TaskCancelable cancelable = statement::cancel;
        activeStatement.set(new StatementRegistration(statement, cancelable));
        runningTask.registerCancelable(cancelable);
        if (runningTask.cancellationToken().isCancelled()) {
            try {
                statement.cancel();
            } catch (Exception ignored) {
                // The runner will still observe the cancellation token.
            }
        }
    }

    @Override
    public void onStatementClosed(Statement statement) {
        StatementRegistration registration = activeStatement.get();
        if (registration != null && registration.statement() == statement
                && activeStatement.compareAndSet(registration, null)) {
            runningTask.clearCancelable(registration.cancelable());
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

    private record StatementRegistration(Statement statement, TaskCancelable cancelable) {
    }
}
