package ai.chat2db.community.domain.core.impl.task.extension;

import ai.chat2db.community.domain.api.model.task.extension.TaskExecutionContext;
import ai.chat2db.community.domain.api.model.task.extension.TaskStatementContext;
import ai.chat2db.community.domain.api.model.task.extension.TaskSubmissionContext;
import ai.chat2db.community.domain.api.service.task.extension.ITaskExecutionGuard;
import ai.chat2db.community.domain.api.service.task.extension.ITaskSubmissionHook;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TaskExtensionManager {

    private final List<ITaskSubmissionHook> submissionHooks;
    private final List<ITaskExecutionGuard> executionGuards;
    private final ThreadLocal<TaskExecutionContext> currentTask = new ThreadLocal<>();

    public TaskExtensionManager(List<ITaskSubmissionHook> submissionHooks,
            List<ITaskExecutionGuard> executionGuards) {
        this.submissionHooks = List.copyOf(submissionHooks);
        this.executionGuards = List.copyOf(executionGuards);
    }

    public void capture(TaskSubmissionContext context) {
        submissionHooks.forEach(hook -> hook.capture(context));
    }

    public void runGuarded(TaskExecutionContext context, Runnable runnable) {
        TaskExecutionContext previous = currentTask.get();
        currentTask.set(context);
        try {
            executionGuards.forEach(guard -> guard.beforeTask(context));
            runnable.run();
        } finally {
            if (previous == null) {
                currentTask.remove();
            } else {
                currentTask.set(previous);
            }
        }
    }

    public void beforeStatement(String sql) {
        if (executionGuards.isEmpty()) {
            return;
        }
        TaskExecutionContext context = currentTask.get();
        if (context == null) {
            throw new IllegalStateException("Task statement guard requires an active task context");
        }
        TaskStatementContext statementContext = new TaskStatementContext(context, sql);
        executionGuards.forEach(guard -> guard.beforeStatement(statementContext));
    }
}
