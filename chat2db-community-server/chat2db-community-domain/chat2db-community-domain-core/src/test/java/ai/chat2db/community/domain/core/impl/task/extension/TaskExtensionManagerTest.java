package ai.chat2db.community.domain.core.impl.task.extension;

import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.task.extension.TaskExecutionContext;
import ai.chat2db.community.domain.api.model.task.extension.TaskOperation;
import ai.chat2db.community.domain.api.model.task.extension.TaskStatementContext;
import ai.chat2db.community.domain.api.model.task.extension.TaskSubmissionContext;
import ai.chat2db.community.domain.api.service.task.extension.ITaskExecutionGuard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskExtensionManagerTest {

    @Test
    void submissionHooksRunInInjectedOrder() {
        List<String> events = new ArrayList<>();
        TaskExtensionManager manager = new TaskExtensionManager(
                List.of(context -> events.add("first"), context -> events.add("second")), List.of());

        manager.capture(submissionContext());

        assertEquals(List.of("first", "second"), events);
    }

    @Test
    void taskAndStatementGuardsRunBeforeProtectedWork() {
        List<String> events = new ArrayList<>();
        ITaskExecutionGuard guard = new ITaskExecutionGuard() {
            @Override
            public void beforeTask(TaskExecutionContext context) {
                events.add("before-task");
            }

            @Override
            public void beforeStatement(TaskStatementContext context) {
                events.add("before-statement:" + context.getSql());
            }
        };
        TaskExtensionManager manager = new TaskExtensionManager(List.of(), List.of(guard));

        manager.runGuarded(submissionContext().toExecutionContext(), () -> {
            events.add("task-body");
            manager.beforeStatement("select 1");
            events.add("statement-execution");
        });

        assertEquals(List.of("before-task", "task-body", "before-statement:select 1", "statement-execution"),
                events);
    }

    @Test
    void emptyExtensionListsPreserveCommunityBehavior() {
        TaskExtensionManager manager = new TaskExtensionManager(List.of(), List.of());
        List<String> events = new ArrayList<>();

        manager.capture(submissionContext());
        manager.beforeStatement("select 1");
        manager.runGuarded(submissionContext().toExecutionContext(), () -> events.add("executed"));

        assertEquals(List.of("executed"), events);
    }

    @Test
    void configuredStatementGuardCannotRunWithoutTaskContext() {
        ITaskExecutionGuard guard = context -> {
        };
        TaskExtensionManager manager = new TaskExtensionManager(List.of(), List.of(guard));

        assertThrows(IllegalStateException.class, () -> manager.beforeStatement("select 1"));
    }

    private static TaskSubmissionContext submissionContext() {
        return new TaskSubmissionContext(42L, TaskType.DATA_FILE_IMPORT, null,
                "shop", null, List.of("orders"), TaskOperation.IMPORT);
    }
}
