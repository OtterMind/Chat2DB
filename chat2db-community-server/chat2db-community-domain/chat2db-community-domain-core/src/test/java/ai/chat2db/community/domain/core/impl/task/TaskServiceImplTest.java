package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.model.LoginUser;
import ai.chat2db.community.tools.util.ContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskServiceImplTest {

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearContext() {
        ContextUtils.removeContext();
    }

    @Test
    void everyTaskReadAndMutationIsIsolatedByUserAndOrganization() throws Exception {
        Path ownedArtifact = Files.writeString(tempDirectory.resolve("owned.csv"), "owned");
        Path otherUserArtifact = Files.writeString(tempDirectory.resolve("other-user.csv"), "other-user");
        Path otherOrganizationArtifact = Files.writeString(
                tempDirectory.resolve("other-organization.csv"), "other-organization");
        OwnershipTaskStorage storage = new OwnershipTaskStorage(List.of(
                task(1L, 10L, 100L, ownedArtifact),
                task(2L, 20L, 100L, otherUserArtifact),
                task(3L, 10L, 200L, otherOrganizationArtifact)));
        TaskServiceImpl service = new TaskServiceImpl(storage, null, new ArtifactService(), taskId -> true);
        ContextUtils.setContext(Context.builder()
                .loginUser(LoginUser.builder().id(10L).build())
                .organizationId(100L)
                .build());
        TaskQuery forgedQuery = new TaskQuery();
        forgedQuery.setUserId(20L);
        forgedQuery.setOrganizationId(200L);

        PageResponse<Task> page = service.list(forgedQuery);

        assertEquals(List.of(1L), page.getData().stream().map(Task::getId).toList());
        assertEquals(10L, forgedQuery.getUserId());
        assertEquals(100L, forgedQuery.getOrganizationId());
        assertEquals(1L, service.get(1L).getId());
        assertNull(service.get(2L));
        assertNull(service.get(3L));
        assertEquals(1, service.listEvents(1L, 0L, 10).size());
        assertEquals(List.of(), service.listEvents(2L, 0L, 10));
        assertEquals(List.of(), service.listEventsBefore(3L, null, 10));
        assertThrows(DataNotFoundException.class, () -> service.delete(2L));
        assertThrows(DataNotFoundException.class, () -> service.delete(3L));
        assertThrows(DataNotFoundException.class, () -> service.resolveArtifact(2L));
        assertThrows(DataNotFoundException.class, () -> service.resolveArtifact(3L));
        TaskDownload download = service.resolveArtifact(1L);
        assertEquals("owned.csv", download.getFileName());
    }

    private Task task(Long id, Long userId, Long organizationId, Path artifact) {
        return Task.builder()
                .id(id)
                .name("task-" + id)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(artifact.toString())
                .userId(userId)
                .organizationId(organizationId)
                .build();
    }

    private static final class OwnershipTaskStorage implements TaskStorage {

        private final Map<Long, Task> tasks = new LinkedHashMap<>();

        private final Map<Long, List<TaskEvent>> events = new LinkedHashMap<>();

        private OwnershipTaskStorage(List<Task> initialTasks) {
            for (Task task : initialTasks) {
                tasks.put(task.getId(), task);
                events.put(task.getId(), List.of(TaskEvent.builder()
                        .taskId(task.getId())
                        .sequence(1L)
                        .message("created")
                        .build()));
            }
        }

        @Override
        public Task create(Task task, List<TaskEvent> initialEvents) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Task> get(Long taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            List<Task> result = tasks.values().stream()
                    .filter(task -> java.util.Objects.equals(task.getUserId(), query.getUserId()))
                    .filter(task -> java.util.Objects.equals(task.getOrganizationId(), query.getOrganizationId()))
                    .toList();
            return PageResponse.of(result, (long) result.size(), 1, 20);
        }

        @Override
        public boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
                TaskStatusPatch patch, TaskEvent lifecycleEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskEvent appendEvent(TaskEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
            return new ArrayList<>(events.getOrDefault(taskId, List.of()));
        }

        @Override
        public List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
            return listEvents(taskId, 0L, limit);
        }

        @Override
        public List<Task> listNonTerminalTasks() {
            return List.of();
        }

        @Override
        public boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
            Task deleted = tasks.remove(taskId);
            if (deleted == null) {
                return false;
            }
            commitAction.run();
            return true;
        }
    }
}
