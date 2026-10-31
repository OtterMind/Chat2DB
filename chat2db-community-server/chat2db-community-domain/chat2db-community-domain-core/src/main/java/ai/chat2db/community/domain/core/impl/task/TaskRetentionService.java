package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.service.task.TaskDeletionService;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the local task history bounded.
 *
 * <p>Only terminal tasks are candidates: a running or pending task is never touched. A task past its
 * retention age is deleted, then the oldest tasks are removed until the task count and the total size
 * of the primary and error artifacts are under their limits. Deleting a task goes through
 * {@link TaskDeletionService}, so artifacts, the pending-deletion queue and the staged upload claim
 * follow the same path as a manual delete.
 */
@Component
@Slf4j
public class TaskRetentionService {

    static final Duration DEFAULT_RETENTION = Duration.ofDays(30);

    static final int DEFAULT_MAX_TERMINAL_TASKS = 500;

    static final long DEFAULT_ARTIFACT_BUDGET_BYTES = 2L * 1024 * 1024 * 1024;

    private final TaskStorage storage;

    private final TaskDeletionService deletions;

    private final Duration retention;

    private final int maxTerminalTasks;

    private final long artifactBudgetBytes;

    private final Clock clock;

    @Autowired
    public TaskRetentionService(TaskStorage storage, TaskDeletionService deletions,
            @Value("${chat2db.task.retention-days:30}") long retentionDays,
            @Value("${chat2db.task.max-terminal-tasks:500}") int maxTerminalTasks,
            @Value("${chat2db.task.artifact-budget-bytes:2147483648}") long artifactBudgetBytes) {
        this(storage, deletions, Duration.ofDays(Math.max(0L, retentionDays)), maxTerminalTasks,
                artifactBudgetBytes, Clock.systemUTC());
    }

    /** Test seam with explicit limits, so the boundaries do not need a 30-day wait. */
    TaskRetentionService(TaskStorage storage, TaskDeletionService deletions, Duration retention,
            int maxTerminalTasks, long artifactBudgetBytes, Clock clock) {
        this.storage = storage;
        this.deletions = deletions;
        this.retention = retention;
        this.maxTerminalTasks = maxTerminalTasks;
        this.artifactBudgetBytes = artifactBudgetBytes;
        this.clock = clock;
    }

    /** Expired tasks from a previous run are removed as soon as the application starts. */
    @PostConstruct
    void cleanupOnStartup() {
        cleanupExpiredTasks();
    }

    /**
     * Drops expired tasks and then the oldest remaining tasks while the count or artifact size is
     * above its limit. The startup pass runs again every hour.
     */
    @Scheduled(fixedDelayString = "${chat2db.task.cleanup-interval-ms:3600000}")
    public void cleanupExpiredTasks() {
        List<Task> terminalTasks;
        try {
            terminalTasks = storage.listTerminalTasks();
        } catch (RuntimeException e) {
            log.warn("Could not list terminal tasks for cleanup", e);
            return;
        }
        List<Task> selected = selectForDeletion(terminalTasks, clock.millis(), retention,
                maxTerminalTasks, artifactBudgetBytes);
        int deleted = 0;
        for (Task task : selected) {
            try {
                deletions.delete(task);
                deleted++;
            } catch (RuntimeException e) {
                // The deletion queue keeps a failed removal for a later retry; one bad task must
                // not stop the cleanup of the others.
                log.warn("Could not delete expired task {}", task.getId(), e);
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} expired task(s) and their artifacts", deleted);
        }
    }

    /**
     * Pure selection step: retention first, then the oldest tasks until both the count and the
     * artifact-byte limits are met. Non-terminal tasks never enter the result.
     */
    static List<Task> selectForDeletion(List<Task> tasks, long nowMillis, Duration retention,
            int maxTerminalTasks, long artifactBudgetBytes) {
        List<Task> ordered = tasks.stream()
                .filter(task -> task.getId() != null && task.getStatus() != null
                        && TaskStatus.isTerminal(task.getStatus()))
                .sorted(Comparator.comparingLong(TaskRetentionService::finishedAt))
                .toList();
        Set<Long> selected = new LinkedHashSet<>();
        if (retention != null && !retention.isZero() && !retention.isNegative()) {
            long cutoff = nowMillis - retention.toMillis();
            for (Task task : ordered) {
                if (finishedAt(task) <= cutoff) {
                    selected.add(task.getId());
                }
            }
        }
        if (maxTerminalTasks > 0 && ordered.size() - selected.size() > maxTerminalTasks) {
            int excess = ordered.size() - selected.size() - maxTerminalTasks;
            for (Task task : ordered) {
                if (excess == 0) {
                    break;
                }
                if (selected.add(task.getId())) {
                    excess--;
                }
            }
        }
        if (artifactBudgetBytes > 0) {
            long total = ordered.stream()
                    .filter(task -> !selected.contains(task.getId()))
                    .mapToLong(TaskRetentionService::artifactBytes)
                    .sum();
            if (total > artifactBudgetBytes) {
                for (Task task : ordered) {
                    if (total <= artifactBudgetBytes) {
                        break;
                    }
                    if (selected.add(task.getId())) {
                        total -= artifactBytes(task);
                    }
                }
            }
        }
        List<Task> result = new ArrayList<>(selected.size());
        for (Task task : ordered) {
            if (selected.contains(task.getId())) {
                result.add(task);
            }
        }
        return result;
    }

    private static long finishedAt(Task task) {
        if (task.getFinishedAt() != null) {
            return task.getFinishedAt().getTime();
        }
        if (task.getCreatedAt() != null) {
            return task.getCreatedAt().getTime();
        }
        // A task without timestamps must not jump ahead of a dated task in the oldest-first order.
        return Long.MAX_VALUE;
    }

    static long artifactBytes(Task task) {
        long total = fileSize(task.getArtifactId());
        if (task.getErrorArtifactId() != null
                && !task.getErrorArtifactId().equals(task.getArtifactId())) {
            total += fileSize(task.getErrorArtifactId());
        }
        return total;
    }

    private static long fileSize(String path) {
        if (path == null || path.isBlank()) {
            return 0L;
        }
        try {
            Path file = Path.of(path);
            return Files.isRegularFile(file) ? Files.size(file) : 0L;
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }
}
