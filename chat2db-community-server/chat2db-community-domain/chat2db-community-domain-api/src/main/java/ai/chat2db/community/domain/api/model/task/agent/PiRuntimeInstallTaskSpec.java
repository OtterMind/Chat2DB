package ai.chat2db.community.domain.api.model.task.agent;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;

public class PiRuntimeInstallTaskSpec extends ImportTaskSpec {

    private AgentRuntimeEnvironmentRequest environment;

    public PiRuntimeInstallTaskSpec(AgentRuntimeEnvironmentRequest environment) {
        setTaskType(TaskType.PI_RUNTIME_INSTALL.name());
        setTaskName("Install Pi Agent runtime");
        setTarget(new TaskTargetSnapshot());
        this.environment = environment;
    }

    public AgentRuntimeEnvironmentRequest getEnvironment() {
        return environment;
    }
}
