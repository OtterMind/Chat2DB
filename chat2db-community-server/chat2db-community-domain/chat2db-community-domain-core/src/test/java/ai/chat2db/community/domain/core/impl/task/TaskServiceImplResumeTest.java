package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.converter.ConnectionContextConverter;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.model.LoginUser;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resume of an owned import task: the stored spec and checkpoint are rebuilt into a new task, and
 * every input that cannot support that (or would silently restart the import) fails explicitly.
 */
class TaskServiceImplResumeTest {

    private static final String CHECKPOINT = "{\"processedRows\":1200,\"targetTable\":\"TARGET_ROWS\","
            + "\"conflictPolicy\":\"INSERT\"}";

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearContext() {
        ContextUtils.removeContext();
    }

    @Test
    void resumeRebuildsTheStoredSpecAndContinuesAfterTheCheckpoint() throws Exception {
        Path csv = writeCsv("ROW_ID,ROW_NAME\n1,ok\n");
        ImportTaskSpec original = spec(csv);
        ResumeStorage storage = new ResumeStorage(task(1L, TaskStatus.FAILED.name(),
                TaskServiceImpl.specJson(original), CHECKPOINT, null));
        AtomicReference<TaskSpec> submitted = new AtomicReference<>();
        TaskServiceImpl service = service(storage, submitted);
        // The original run may have failed; the resume must still work and leave it untouched.
        Long resumedId = service.resumeImport(1L);

        assertEquals(2L, resumedId);
        ImportTaskSpec resumed = (ImportTaskSpec) submitted.get();
        assertNotNull(resumed);
        assertEquals(1200L, resumed.getResumeFromRow());
        assertNull(resumed.getDuplicatePolicy(),
                "a serial checkpoint is exact, so its duplicate policy must not be rewritten");
        assertEquals(csv.toString(), resumed.getSourceFile());
        assertEquals("CSV", resumed.getFormat());
        assertEquals("TARGET_ROWS", resumed.getTarget().getTableName());
        assertEquals(1, resumed.getColumnMappings().size());
        assertEquals("import", resumed.getTaskName());
        assertEquals(1L, resumed.getResumeRootTaskId());
        assertEquals(1L, resumed.getResumeParentTaskId());
        assertEquals(1, resumed.getResumeAttempt());
        assertEquals("import", resumed.getResumeRootTaskName());

        Task source = storage.get(1L).orElseThrow();
        assertEquals(TaskStatus.FAILED.name(), source.getStatus());
        assertEquals(CHECKPOINT, source.getCheckpointJson());
        // The new task stores its own spec, so a second resume has a self-contained source.
        assertNotNull(storage.get(resumedId).orElseThrow().getSpecJson());
    }

    @Test
    void repeatedResumePreservesRootAndIncrementsAttemptWithoutRenaming() throws Exception {
        ImportTaskSpec original = spec(writeCsv("ROW_ID,ROW_NAME\n1,ok\n"));
        original.setResumeRootTaskId(1L);
        original.setResumeAttempt(2);
        original.setResumeRootTaskName("original import");
        ResumeStorage storage = new ResumeStorage(task(7L, TaskStatus.CANCELLED.name(),
                TaskServiceImpl.specJson(original), CHECKPOINT, null));
        AtomicReference<TaskSpec> submitted = new AtomicReference<>();
        service(storage, submitted).resumeImport(7L);
        ImportTaskSpec resumed = (ImportTaskSpec) submitted.get();
        assertEquals(1L, resumed.getResumeRootTaskId());
        assertEquals(7L, resumed.getResumeParentTaskId());
        assertEquals(3, resumed.getResumeAttempt());
        assertEquals("original import", resumed.getTaskName());
        assertEquals(TaskStatus.CANCELLED.name(), storage.get(7L).orElseThrow().getStatus());
    }

    @Test
    void resumeWorksForACancelledTaskAsWell() throws Exception {
        Path csv = writeCsv("ROW_ID,ROW_NAME\n1,ok\n");
        ResumeStorage storage = new ResumeStorage(task(1L, TaskStatus.CANCELLED.name(),
                TaskServiceImpl.specJson(spec(csv)), CHECKPOINT, null));
        AtomicReference<TaskSpec> submitted = new AtomicReference<>();

        assertEquals(2L, service(storage, submitted).resumeImport(1L));
        assertEquals(1200L, ((ImportTaskSpec) submitted.get()).getResumeFromRow());
        assertNull(((ImportTaskSpec) submitted.get()).getDuplicatePolicy());
        assertEquals(TaskStatus.CANCELLED.name(), storage.get(1L).orElseThrow().getStatus());
    }

    @Test
    void resumeOfAFastImportSkipsRowsCommittedBeyondTheCheckpoint() throws Exception {
        Path csv = writeCsv("ROW_ID,ROW_NAME\n1,ok\n");
        ImportTaskSpec original = spec(csv);
        original.setMode("FAST");
        ResumeStorage storage = new ResumeStorage(task(1L, TaskStatus.CANCELLED.name(),
                TaskServiceImpl.specJson(original), CHECKPOINT, null));
        AtomicReference<TaskSpec> submitted = new AtomicReference<>();

        assertEquals(2L, service(storage, submitted).resumeImport(1L));

        ImportTaskSpec resumed = (ImportTaskSpec) submitted.get();
        assertEquals(1200L, resumed.getResumeFromRow());
        assertEquals("IGNORE", resumed.getDuplicatePolicy(),
                "parallel workers may have stored rows past the checkpoint, so the continuation "
                        + "must skip the overlap instead of failing on it");
    }

    @Test
    void resumeRejectsEveryInputThatCannotBeContinued() throws Exception {
        Path csv = writeCsv("ROW_ID,ROW_NAME\n1,ok\n");
        String specJson = TaskServiceImpl.specJson(spec(csv));
        Map<String, Object[]> cases = new LinkedHashMap<>();
        cases.put("no spec at all", new Object[] {task(1L, TaskStatus.FAILED.name(), null, CHECKPOINT, null),
                "task.resume.missingSpec"});
        cases.put("no checkpoint", new Object[] {task(1L, TaskStatus.FAILED.name(), specJson, null, null),
                "task.resume.missingCheckpoint"});
        cases.put("unreadable spec", new Object[] {
                task(1L, TaskStatus.FAILED.name(), "{not json", CHECKPOINT, null), "task.resume.invalidSpec"});
        cases.put("unreadable checkpoint", new Object[] {
                task(1L, TaskStatus.FAILED.name(), specJson, "[1,2]", null), "task.resume.invalidCheckpoint"});
        cases.put("not a resumable format", new Object[] {
                task(1L, TaskStatus.FAILED.name(), TaskServiceImpl.specJson(sqlSpec(csv)), CHECKPOINT, null),
                "task.resume.unsupportedType"});
        cases.put("source file gone", new Object[] {
                task(1L, TaskStatus.FAILED.name(), TaskServiceImpl.specJson(spec(tempDirectory.resolve("gone.csv"))),
                        CHECKPOINT, null), "task.resume.sourceMissing"});

        for (Map.Entry<String, Object[]> entry : cases.entrySet()) {
            ResumeStorage storage = new ResumeStorage((Task) entry.getValue()[0]);
            TaskServiceImpl service = service(storage, new AtomicReference<>());
            BusinessException failure = assertThrows(BusinessException.class, () -> service.resumeImport(1L),
                    entry.getKey());
            assertEquals(entry.getValue()[1], failure.getCode(), entry.getKey());
            assertEquals(List.of(), storage.createdTaskIds(), entry.getKey() + " must not create a task");
        }
    }

    @Test
    void resumeIsIsolatedByOwner() throws Exception {
        Path csv = writeCsv("ROW_ID,ROW_NAME\n1,ok\n");
        Task foreign = task(1L, TaskStatus.FAILED.name(), TaskServiceImpl.specJson(spec(csv)), CHECKPOINT, null);
        foreign.setUserId(20L);
        foreign.setOrganizationId(200L);
        ResumeStorage storage = new ResumeStorage(foreign);

        assertThrows(DataNotFoundException.class, () -> service(storage, new AtomicReference<>()).resumeImport(1L));
        assertEquals(List.of(), storage.createdTaskIds());
    }

    @Test
    void errorArtifactIsServedForFailedTasksAndRespectsOwnership() throws Exception {
        Path errorFile = Files.writeString(tempDirectory.resolve("error-rows.csv"), "rowNumber,reason,content\n");
        ResumeStorage storage = new ResumeStorage(task(1L, TaskStatus.FAILED.name(), null, null,
                errorFile.toString()));
        TaskServiceImpl service = service(storage, new AtomicReference<>());

        TaskDownload download = service.resolveErrorArtifact(1L);

        assertEquals("error-rows.csv", download.getFileName());
        assertTrue(download.getFileUri().endsWith("error-rows.csv"));
        assertThrows(DataNotFoundException.class, () -> service.resolveErrorArtifact(99L));

        storage.tasks.put(2L, task(2L, TaskStatus.SUCCESS.name(), null, null, null));
        assertThrows(DataNotFoundException.class, () -> service.resolveErrorArtifact(2L));
        storage.tasks.put(3L, task(3L, TaskStatus.FAILED.name(), null, null,
                tempDirectory.resolve("missing.csv").toString()));
        assertThrows(DataNotFoundException.class, () -> service.resolveErrorArtifact(3L));

        Task foreign = task(4L, TaskStatus.FAILED.name(), null, null, errorFile.toString());
        foreign.setUserId(20L);
        foreign.setOrganizationId(200L);
        storage.tasks.put(4L, foreign);
        assertThrows(DataNotFoundException.class, () -> service.resolveErrorArtifact(4L));
    }

@Test
    void resumeSubmitsTheConnectionOfTheStoredDataSource() throws Exception {
        Path csv = writeCsv("ROW_ID,ROW_NAME\n1,ok\n");
        ResumeStorage storage = new ResumeStorage(task(1L, TaskStatus.FAILED.name(),
                TaskServiceImpl.specJson(spec(csv)), CHECKPOINT, null));
        AtomicReference<TaskSpec> submitted = new AtomicReference<>();
        AtomicReference<ConnectInfo> submittedConnection = new AtomicReference<>();

        assertEquals(2L, service(storage, submitted, submittedConnection).resumeImport(1L));

        ConnectInfo connection = submittedConnection.get();
        assertNotNull(connection, "the resumed task must be submitted with a connection");
        assertEquals(1L, connection.getDataSourceId());
        assertEquals("MYSQL", connection.getDbType());
        assertEquals("jdbc:mysql://localhost:3306/bench", connection.getUrl());
        assertEquals("root", connection.getUser());
        assertEquals("secret", connection.getPassword());
        // The database of the original run is kept, so the resumed run reaches the same schema.
        assertEquals("bench", connection.getDatabaseName());
    }

    @Test
    void resumeRejectsATargetWhoseDataSourceIsGone() throws Exception {
        Path csv = writeCsv("ROW_ID,ROW_NAME\n1,ok\n");
        ImportTaskSpec withoutDataSource = spec(csv);
        withoutDataSource.setTarget(TaskTargetSnapshot.builder().databaseName("bench")
                .tableName("TARGET_ROWS").build());
        ImportTaskSpec deletedDataSource = spec(csv);
        deletedDataSource.setTarget(TaskTargetSnapshot.builder().dataSourceId(99L).databaseName("bench")
                .tableName("TARGET_ROWS").build());

        for (ImportTaskSpec original : List.of(withoutDataSource, deletedDataSource)) {
            ResumeStorage storage = new ResumeStorage(task(1L, TaskStatus.FAILED.name(),
                    TaskServiceImpl.specJson(original), CHECKPOINT, null));
            TaskServiceImpl service = service(storage, new AtomicReference<>());

            BusinessException failure = assertThrows(BusinessException.class, () -> service.resumeImport(1L));
            assertEquals("task.resume.dataSourceMissing", failure.getCode());
            assertEquals(List.of(), storage.createdTaskIds(), "a task without a connection must not be created");
        }
    }


    private TaskServiceImpl service(ResumeStorage storage, AtomicReference<TaskSpec> submitted) {
        return service(storage, submitted, new AtomicReference<>());
    }

    private TaskServiceImpl service(ResumeStorage storage, AtomicReference<TaskSpec> submitted,
            AtomicReference<ConnectInfo> submittedConnection) {
        ContextUtils.setContext(Context.builder()
                .loginUser(LoginUser.builder().id(10L).build())
                .organizationId(100L)
                .build());
        return new TaskServiceImpl(storage, capturingManager(storage, submitted, submittedConnection),
                new TaskDeletionServiceImpl(storage, new ArtifactServiceImpl(), null),
                dataSourceService(savedDataSource()), new ConnectionContextConverter());
    }

    private LocalTaskManager capturingManager(ResumeStorage storage, AtomicReference<TaskSpec> submitted,
            AtomicReference<ConnectInfo> submittedConnection) {
        return new LocalTaskManager(null, null, null, null, null, 1, 1) {
            @Override
            void validate(TaskSpec spec) {
            }

            @Override
            <S extends TaskSpec> Task submit(Task task, TaskEvent createdEvent, S spec, Context context,
                    ConnectInfo connectInfo) {
                submitted.set(spec);
                submittedConnection.set(connectInfo);
                return storage.create(task, createdEvent);
            }
        };
    }

    private WorkspaceDataSource savedDataSource() {
        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        dataSource.setId(1L);
        dataSource.setAlias("@localhost");
        dataSource.setType("MYSQL");
        dataSource.setUrl("jdbc:mysql://localhost:3306/bench");
        dataSource.setUser("root");
        dataSource.setPassword("secret");
        return dataSource;
    }

    /** Resolves only the saved data source of this test; any other id is reported as missing. */
    private IDbWorkspaceDataSourceService dataSourceService(WorkspaceDataSource dataSource) {
        return (IDbWorkspaceDataSourceService) Proxy.newProxyInstance(
                IDbWorkspaceDataSourceService.class.getClassLoader(),
                new Class<?>[] {IDbWorkspaceDataSourceService.class},
                (proxy, method, args) -> {
                    if ("queryDisplayDataSourceById".equals(method.getName())) {
                        return dataSource.getId().equals(args[0]) ? dataSource : null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private Path writeCsv(String content) throws Exception {
        Path csv = tempDirectory.resolve("input.csv");
        Files.writeString(csv, content);
        return csv;
    }

    private ImportTaskSpec spec(Path csv) {
        return ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .taskName("import")
                .sourceFile(csv.toString())
                .format("CSV")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).databaseName("bench")
                        .tableName("TARGET_ROWS").build())
                .columnMappings(List.of(new ImportColumnMapping("ROW_ID", "ID")))
                .build();
    }

    private ImportTaskSpec sqlSpec(Path csv) {
        return ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(csv.toString())
                .format("SQL")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("TARGET_ROWS").build())
                .build();
    }

    private Task task(Long id, String status, String specJson, String checkpointJson, String errorArtifactId) {
        return Task.builder()
                .id(id)
                .name("import")
                .status(status)
                .specJson(specJson)
                .checkpointJson(checkpointJson)
                .errorArtifactId(errorArtifactId)
                .userId(10L)
                .organizationId(100L)
                .build();
    }

    private static final class ResumeStorage implements TaskStorage {

        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, Task> tasks = new LinkedHashMap<>();
        private final List<Long> createdTaskIds = new ArrayList<>();

        private ResumeStorage(Task task) {
            tasks.put(task.getId(), task);
            ids.set(task.getId());
        }

        List<Long> createdTaskIds() {
            return createdTaskIds;
        }

        @Override
        public Task create(Task task, TaskEvent createdEvent) {
            task.setId(ids.incrementAndGet());
            task.setStatus(TaskStatus.PENDING.name());
            tasks.put(task.getId(), task);
            createdTaskIds.add(task.getId());
            createdEvent.setTaskId(task.getId());
            return task;
        }

        @Override
        public Optional<Task> get(Long taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            return PageResponse.of(List.copyOf(tasks.values()), (long) tasks.size(), 1, 20);
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
            return List.of();
        }

        @Override
        public List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
            return List.of();
        }

        @Override
        public List<Task> listNonTerminalTasks() {
            return List.of();
        }

        @Override
        public boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
            return false;
        }
    }
}
