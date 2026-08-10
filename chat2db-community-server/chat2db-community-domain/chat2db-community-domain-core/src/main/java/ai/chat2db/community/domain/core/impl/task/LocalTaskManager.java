package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class LocalTaskManager {

    private final TaskStorage taskStorage;

    private final TaskExecutorRegistry taskExecutorRegistry;

    private final ArtifactService artifactService;

    private final RunningTaskRegistry runningTaskRegistry = new RunningTaskRegistry();

    private final ThreadPoolExecutor executor;

    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private boolean preparingForExit;

    public LocalTaskManager(TaskStorage taskStorage, TaskExecutorRegistry taskExecutorRegistry,
            ArtifactService artifactService,
            @Value("${chat2db.task.max-concurrency:4}") int maxConcurrency,
            @Value("${chat2db.task.queue-capacity:100}") int queueCapacity) {
        this.taskStorage = taskStorage;
        this.taskExecutorRegistry = taskExecutorRegistry;
        this.artifactService = artifactService;
        int concurrency = Math.max(1, maxConcurrency);
        int capacity = Math.max(1, queueCapacity);
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), new TaskThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    void reconcileInterruptedTasks() {
        for (Task task : taskStorage.listNonTerminalTasks()) {
            failPersistedTask(task, TaskErrorCode.APPLICATION_TERMINATED.name(),
                    TaskEventCode.APPLICATION_TERMINATED.name(),
                    "The application terminated before the task completed");
            cleanupInterruptedArtifacts(task.getId());
        }
    }

    <S extends TaskSpec> Task submit(Task task, TaskEvent createdEvent, S spec, Context context,
            ConnectInfo connectInfo) {
        lifecycleLock.lock();
        try {
            if (preparingForExit) {
                throw new RejectedExecutionException("The application is preparing to exit");
            }
            Task persistedTask = taskStorage.create(task, createdEvent);
            schedule(persistedTask, spec, context, connectInfo);
            return persistedTask;
        } finally {
            lifecycleLock.unlock();
        }
    }

    void validate(TaskSpec spec) {
        if (spec == null || spec.getTaskType() == null) {
            throw new IllegalArgumentException("Task type is required");
        }
        taskExecutorRegistry.require(spec);
    }

    Task cancel(Long taskId) {
        RunningTask runningTask = runningTaskRegistry.get(taskId);
        Task task = taskStorage.get(taskId).orElse(null);
        if (task == null || task.getStatus() == null || TaskStatus.isTerminal(task.getStatus())
                || runningTask == null) {
            return task;
        }
        runningTask.completionLock().lock();
        try {
            task = taskStorage.get(taskId).orElse(task);
            if (TaskStatus.isTerminal(task.getStatus()) || runningTask.isClosed()) {
                return task;
            }
            if (TaskStatus.PENDING.name().equals(task.getStatus())) {
                runningTask.requestCancellation(false);
                Date now = new Date();
                taskStorage.compareAndSetStatus(taskId, TaskStatus.PENDING.name(), TaskStatus.CANCELLED.name(),
                        TaskStatusPatch.builder()
                                .progressMessage("Task cancelled before execution")
                                .finishedAt(now)
                                .updatedAt(now)
                                .build(),
                        event(TaskEventCode.TASK_CANCELLED.name(), TaskEventLevel.INFO.name(),
                                "Task cancelled before execution"));
                runningTask.close();
                runningTaskRegistry.remove(taskId, runningTask);
            } else if (TaskStatus.RUNNING.name().equals(task.getStatus())) {
                taskStorage.appendEvent(TaskEvent.builder()
                        .taskId(taskId)
                        .level(TaskEventLevel.INFO.name())
                        .code(TaskEventCode.TASK_CANCEL_ACCEPTED.name())
                        .message("Task cancellation accepted")
                        .details(Collections.emptyMap())
                        .build());
                taskStorage.updateProgressIfRunning(taskId, TaskProgress.builder()
                        .progress(task.getProgress())
                        .stage(task.getStage())
                        .message("Cancelling task")
                        .build());
                runningTask.requestCancellation(true);
            }
            return taskStorage.get(taskId).orElse(task);
        } finally {
            runningTask.completionLock().unlock();
        }
    }

    int activeTaskCount(Long userId, Long organizationId) {
        return (int) taskStorage.listNonTerminalTasks().stream()
                .filter(task -> belongsTo(task, userId, organizationId))
                .count();
    }

    void prepareForUserExit(Long userId, Long organizationId) {
        terminateActiveTasks(TaskErrorCode.USER_EXITED.name(), TaskEventCode.USER_EXITED.name(),
                "The user exited the application while the task was running",
                new TaskOwner(userId, organizationId));
    }

    void abortUserExit() {
        lifecycleLock.lock();
        try {
            preparingForExit = false;
        } finally {
            lifecycleLock.unlock();
        }
    }

    @PreDestroy
    void shutdown() {
        terminateActiveTasks(TaskErrorCode.APPLICATION_TERMINATED.name(),
                TaskEventCode.APPLICATION_TERMINATED.name(),
                "The application terminated before the task completed", null);
        executor.shutdownNow();
    }

    private void terminateActiveTasks(String errorCode, String eventCode, String message, TaskOwner owner) {
        lifecycleLock.lock();
        try {
            if (preparingForExit && owner != null) {
                return;
            }
            preparingForExit = true;
            List<Task> activeTasks = taskStorage.listNonTerminalTasks();
            for (Task task : activeTasks) {
                if (owner != null && !belongsTo(task, owner.userId(), owner.organizationId())) {
                    continue;
                }
                RunningTask runningTask = runningTaskRegistry.get(task.getId());
                if (runningTask == null) {
                    failPersistedTask(task, errorCode, eventCode, message);
                    cleanupInterruptedArtifacts(task.getId());
                    continue;
                }
                runningTask.completionLock().lock();
                try {
                    Task currentTask = taskStorage.get(task.getId()).orElse(task);
                    if (TaskStatus.isTerminal(currentTask.getStatus())) {
                        continue;
                    }
                    runningTask.requestCancellation(TaskStatus.RUNNING.name().equals(currentTask.getStatus()));
                    failPersistedTask(currentTask, errorCode, eventCode, message);
                    cleanupInterruptedArtifacts(task.getId());
                    runningTask.close();
                    runningTaskRegistry.remove(task.getId(), runningTask);
                } finally {
                    runningTask.completionLock().unlock();
                }
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void failPersistedTask(Task task, String errorCode, String eventCode, String message) {
        Date now = new Date();
        taskStorage.compareAndSetStatus(task.getId(), task.getStatus(), TaskStatus.FAILED.name(),
                TaskStatusPatch.builder()
                        .stage(TaskStage.FAILED.name())
                        .progressMessage(message)
                        .errorCode(errorCode)
                        .errorMessage(message)
                        .finishedAt(now)
                        .updatedAt(now)
                        .build(),
                event(eventCode, TaskEventLevel.ERROR.name(), message));
    }

    private void cleanupInterruptedArtifacts(Long taskId) {
        long afterSequence = 0L;
        String temporaryPath = null;
        String publishedPath = null;
        while (true) {
            List<TaskEvent> events = taskStorage.listEvents(taskId, afterSequence, TaskConstants.MAX_EVENT_LIMIT);
            if (events.isEmpty()) {
                break;
            }
            for (TaskEvent event : events) {
                Map<String, Object> details = event.getDetails();
                if (TaskEventCode.ARTIFACT_PREPARED.name().equals(event.getCode())) {
                    temporaryPath = detail(details, TaskConstants.ARTIFACT_TEMPORARY_PATH_DETAIL_KEY);
                } else if (TaskEventCode.ARTIFACT_PUBLISHED.name().equals(event.getCode())) {
                    publishedPath = detail(details, TaskConstants.ARTIFACT_ID_DETAIL_KEY);
                }
            }
            long nextSequence = events.get(events.size() - 1).getSequence();
            if (nextSequence <= afterSequence || events.size() < TaskConstants.MAX_EVENT_LIMIT) {
                break;
            }
            afterSequence = nextSequence;
        }
        artifactService.cleanupInterruptedArtifact(taskId, temporaryPath, publishedPath);
    }

    private String detail(Map<String, Object> details, String key) {
        if (details == null || details.get(key) == null) {
            return null;
        }
        return String.valueOf(details.get(key));
    }

    private boolean belongsTo(Task task, Long userId, Long organizationId) {
        return Objects.equals(task.getUserId(), userId)
                && Objects.equals(task.getOrganizationId(), organizationId);
    }

    private <S extends TaskSpec> void schedule(Task task, S spec, Context context, ConnectInfo connectInfo) {
        TaskExecutor<S> taskExecutor = taskExecutorRegistry.require(spec);
        RunningTask runningTask = new RunningTask(task.getId());
        TaskSubmission<S> submission = new TaskSubmission<>(task.getId(), spec, context,
                connectInfo == null ? null : connectInfo.copy());
        TaskRunner<S> taskRunner = new TaskRunner<>(submission, runningTask, runningTaskRegistry, taskStorage,
                taskExecutor, artifactService);
        FutureTask<Void> futureTask = new FutureTask<>(taskRunner, null);
        runningTask.setFuture(futureTask);
        runningTaskRegistry.register(runningTask);
        try {
            executor.execute(futureTask);
        } catch (RejectedExecutionException e) {
            runningTaskRegistry.remove(task.getId(), runningTask);
            runningTask.close();
            Date now = new Date();
            taskStorage.compareAndSetStatus(task.getId(), TaskStatus.PENDING.name(), TaskStatus.FAILED.name(),
                    TaskStatusPatch.builder()
                            .stage(TaskStage.FAILED.name())
                            .errorCode(TaskErrorCode.TASK_EXECUTOR_REJECTED.name())
                            .errorMessage("Too many tasks are waiting to execute")
                            .progressMessage("Task submission rejected")
                            .finishedAt(now)
                            .updatedAt(now)
                            .build(),
                    event(TaskEventCode.TASK_FAILED.name(), TaskEventLevel.ERROR.name(),
                            "Task submission rejected"));
        }
    }

    private TaskEvent event(String code, String level, String message) {
        return TaskEvent.builder()
                .level(level)
                .code(code)
                .message(message)
                .details(Collections.emptyMap())
                .build();
    }

    private static final class TaskThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "chat2db-task-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        }
    }

    private record TaskOwner(Long userId, Long organizationId) {
    }
}
