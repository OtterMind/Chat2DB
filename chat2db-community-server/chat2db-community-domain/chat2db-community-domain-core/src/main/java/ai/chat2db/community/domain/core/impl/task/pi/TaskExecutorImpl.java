package ai.chat2db.community.domain.core.impl.task.pi;

import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.task.agent.PiRuntimeInstallTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeInstallation;

public class TaskExecutorImpl implements TaskExecutor<PiRuntimeInstallTaskSpec> {

    private final IAgentRuntimeInstallation installation;

    public TaskExecutorImpl(IAgentRuntimeInstallation installation) {
        this.installation = installation;
    }

    @Override
    public String taskType() {
        return TaskType.PI_RUNTIME_INSTALL.name();
    }

    @Override
    public Class<PiRuntimeInstallTaskSpec> specType() {
        return PiRuntimeInstallTaskSpec.class;
    }

    @Override
    public void execute(PiRuntimeInstallTaskSpec spec, TaskExecutionContext context) {
        context.reportProgress(5, TaskStage.STARTING.name(), "Checking Pi runtime environment");
        try {
            installation.install(spec.getEnvironment());
        } catch (Exception error) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Pi runtime installation failed: " + error.getMessage(), error);
        }
        context.reportProgress(95, TaskStage.FINALIZING.name(), "Pi runtime installed");
    }
}
