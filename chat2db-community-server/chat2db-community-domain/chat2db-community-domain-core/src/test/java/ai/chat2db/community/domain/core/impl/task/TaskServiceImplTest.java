package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.task.ImportPreview;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.model.LoginUser;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceImplTest {

    private static final String RESUME_TEST_DB_TYPE = "TASK_RESUME_TEST";

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearContext() {
        ContextUtils.removeContext();
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.remove(RESUME_TEST_DB_TYPE);
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
        TaskServiceImpl service = new TaskServiceImpl(storage, null, new TaskDeletionServiceImpl(storage, new ArtifactServiceImpl()));
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

    @Test
    void importAllowlistResolvesSymbolicLinksBeforeAuthorizingTheSource() throws Exception {
        Path allowed = Files.createDirectory(tempDirectory.resolve("allowed"));
        Path outside = Files.createDirectory(tempDirectory.resolve("outside"));
        Files.writeString(outside.resolve("data.csv"), "id\n1\n");
        Path link = allowed.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Symbolic links are unavailable: " + unavailable.getMessage());
        }
        TaskServiceImpl service = new TaskServiceImpl(new OwnershipTaskStorage(List.of()), null,
                new ArtifactService());
        Field field = TaskServiceImpl.class.getDeclaredField("importAllowedRoots");
        field.setAccessible(true);
        field.set(service, allowed.toString());
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(link.resolve("data.csv").toString())
                .build();

        assertThrows(BusinessException.class, () -> service.submitImport(spec));
    }

    @Test
    void resumeRebuildsConnectionFromPersistedTargetAndClearsTemporaryContext() {
        AtomicReference<DbConnectionContextRequest> boundRequest = new AtomicReference<>();
        ConnectInfo complete = new ConnectInfo();
        complete.setDataSourceId(42L);
        complete.setDbType(RESUME_TEST_DB_TYPE);
        complete.setDatabase("archive");
        complete.setSchemaName("audit");
        complete.setPassword("not-persisted-in-task");
        IDbConnectionContextService connectionContexts = (IDbConnectionContextService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IDbConnectionContextService.class},
                (proxy, method, args) -> {
                    if ("bind".equals(method.getName())) {
                        boundRequest.set((DbConnectionContextRequest) args[0]);
                        Chat2DBContext.putContext(complete);
                    } else if ("clear".equals(method.getName())) {
                        Chat2DBContext.removeContext();
                    }
                    return null;
                });
        TaskServiceImpl service = new TaskServiceImpl(new OwnershipTaskStorage(List.of()), null,
                new ArtifactService(), connectionContexts);
        DBConfig config = new DBConfig();
        config.setDbType(RESUME_TEST_DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        Chat2DBContext.PLUGIN_MAP.put(RESUME_TEST_DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }
        });
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .target(TaskTargetSnapshot.builder()
                        .dataSourceId(42L)
                        .databaseName("archive")
                        .schemaName("audit")
                        .tableName("events")
                        .build())
                .build();

        ConnectInfo resolved = service.resumeConnectInfo(spec);

        assertEquals(42L, boundRequest.get().getDataSourceId());
        assertEquals("archive", boundRequest.get().getDatabaseName());
        assertEquals("audit", boundRequest.get().getSchemaName());
        assertEquals("not-persisted-in-task", resolved.getPassword());
        assertNull(Chat2DBContext.getConnectInfo());
    }

    @Test
    void importPreviewReadsMetadataWhileThePersistedTargetContextIsBound() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("preview.csv"), "ID\n1\n");
        AtomicBoolean metadataReadWithBoundContext = new AtomicBoolean();
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:task_preview")) {
            ConnectInfo complete = new ConnectInfo();
            complete.setDataSourceId(42L);
            complete.setDbType(RESUME_TEST_DB_TYPE);
            complete.setDatabase("archive");
            complete.setSchemaName("audit");
            complete.setConnection(connection);
            IDbConnectionContextService connectionContexts = connectionContexts(complete, new AtomicReference<>());
            TaskServiceImpl service = new TaskServiceImpl(new OwnershipTaskStorage(List.of()), null,
                    new ArtifactService(), connectionContexts);
            DBConfig config = new DBConfig();
            config.setDbType(RESUME_TEST_DB_TYPE);
            config.setDefaultDriverConfig(new DriverConfig());
            IDbMetaData metadata = (IDbMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {IDbMetaData.class}, (proxy, method, args) -> {
                        if ("getSQLIdentifierProcessor".equals(method.getName())) {
                            return new ai.chat2db.spi.DefaultSQLIdentifierProcessor();
                        }
                        if ("tables".equals(method.getName())) {
                            return List.of(ai.chat2db.community.domain.api.model.metadata.Table.builder()
                                    .name("events").build());
                        }
                        if ("columns".equals(method.getName())) {
                            metadataReadWithBoundContext.set(Chat2DBContext.getConnectInfo() == complete);
                            return List.of(TableColumn.builder().name("ID").columnType("INTEGER").build());
                        }
                        return null;
                    });
            Chat2DBContext.PLUGIN_MAP.put(RESUME_TEST_DB_TYPE, new IPlugin() {
                @Override
                public DBConfig getDBConfig() {
                    return config;
                }

                @Override
                public IDbMetaData getDbMetaData() {
                    return metadata;
                }
            });
            ImportTaskSpec spec = ImportTaskSpec.builder()
                    .format("CSV")
                    .sourceFile(source.toString())
                    .target(TaskTargetSnapshot.builder()
                            .dataSourceId(42L)
                            .databaseName("archive")
                            .schemaName("audit")
                            .tableName("events")
                            .build())
                    .build();

            IImportFileStagingService staging = (IImportFileStagingService) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {IImportFileStagingService.class},
                    (proxy, method, args) -> {
                        assertEquals("resolve", method.getName(), "Preview must not claim or delete a source");
                        assertEquals("staged-preview-id", args[0]);
                        return source.toFile();
                    });
            TaskServiceImpl stagedService = new TaskServiceImpl(new OwnershipTaskStorage(List.of()), null,
                    new ArtifactService(), connectionContexts, staging);
            spec.setImportFileId("staged-preview-id");
            spec.setSourceFile(tempDirectory.resolve("untrusted.csv").toString());
            assertEquals(List.of("ID"), stagedService.previewImport(spec).getFileColumns());
            assertEquals(source.toFile().getAbsolutePath(), spec.getSourceFile());
            assertTrue(metadataReadWithBoundContext.get());
            assertNull(Chat2DBContext.getConnectInfo());
        }
    }

    private IDbConnectionContextService connectionContexts(ConnectInfo complete,
            AtomicReference<DbConnectionContextRequest> boundRequest) {
        return (IDbConnectionContextService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IDbConnectionContextService.class}, (proxy, method, args) -> {
                    if ("bind".equals(method.getName())) {
                        boundRequest.set((DbConnectionContextRequest) args[0]);
                        Chat2DBContext.putContext(complete);
                    } else if ("clear".equals(method.getName())) {
                        Chat2DBContext.removeContext();
                    }
                    return null;
                });
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
        public Task create(Task task, TaskEvent createdEvent) {
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

        @Override
        public List<TaskArtifact> listArtifacts(Long taskId) {
            return List.of();
        }

        @Override
        public void saveArtifact(Long taskId, TaskArtifact artifact) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteArtifact(Long taskId, String artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Task> listResumableTasks() {
            return List.of();
        }

        @Override
        public void saveResumeState(Long taskId, ResumeState state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ResumeState> listResumeStates(Long taskId) {
            return List.of();
        }

        @Override
        public void clearResumeStates(Long taskId) {
            throw new UnsupportedOperationException();
        }
    }
}
