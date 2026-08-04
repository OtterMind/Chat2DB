package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordUpdateRequest;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.api.service.task.ITaskAsyncCall;
import ai.chat2db.community.domain.api.service.task.ITaskSchedulerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskRecordServiceImplTest {

    @ParameterizedTest
    @ValueSource(strings = {"FINISHED", "STOP", "ERROR"})
    void rejectedCancellationDoesNotOverwriteTerminalTask(String status) {
        AtomicReference<TaskRecordUpdateRequest> update = new AtomicReference<>();
        TaskRecordServiceImpl service = service(false, task(status), update);

        service.stopTask(42L);

        assertNull(update.get());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"INIT", "PROCESSING", "RUNNING"})
    void missingThreadCanStopPersistedNonTerminalTask(String status) {
        AtomicReference<TaskRecordUpdateRequest> update = new AtomicReference<>();
        TaskRecordServiceImpl service = service(false, task(status), update);

        service.stopTask(42L);

        assertEquals("STOP", update.get().getTaskStatus());
        assertEquals("", update.get().getDownloadUrl());
    }

    @Test
    void missingThreadDoesNotCreateMissingTask() {
        AtomicReference<TaskRecordUpdateRequest> update = new AtomicReference<>();
        TaskRecordServiceImpl service = service(false, null, update);

        service.stopTask(42L);

        assertNull(update.get());
    }

    @Test
    void acceptedCancellationPersistsStopAndClearsDownloadUrl() {
        AtomicReference<TaskRecordUpdateRequest> update = new AtomicReference<>();
        TaskRecordServiceImpl service = service(true, null, update);

        service.stopTask(42L);

        assertEquals(42L, update.get().getId());
        assertEquals("STOP", update.get().getTaskStatus());
        assertEquals("", update.get().getDownloadUrl());
    }

    private static TaskRecordServiceImpl service(
            boolean cancellationAccepted, Task persistedTask,
            AtomicReference<TaskRecordUpdateRequest> update) {
        IWorkspaceStorageFacade storageFacade = (IWorkspaceStorageFacade) Proxy.newProxyInstance(
                TaskRecordServiceImplTest.class.getClassLoader(),
                new Class<?>[]{IWorkspaceStorageFacade.class},
                (proxy, method, args) -> {
                    if ("updateTask".equals(method.getName())) {
                        update.set((TaskRecordUpdateRequest) args[0]);
                    } else if ("getTask".equals(method.getName())) {
                        return persistedTask;
                    }
                    return null;
                });
        ITaskSchedulerService schedulerService = new ITaskSchedulerService() {
            @Override
            public ITaskAsyncCall asyncCall(Long taskId) {
                return null;
            }

            @Override
            public void submit(Long taskId, AsyncContext asyncContext, Runnable runnable) {
            }

            @Override
            public boolean cancel(Long taskId) {
                return cancellationAccepted;
            }
        };
        return new TaskRecordServiceImpl(storageFacade, schedulerService);
    }

    private static Task task(String status) {
        Task task = new Task();
        task.setId(42L);
        task.setTaskStatus(status);
        return task;
    }
}
