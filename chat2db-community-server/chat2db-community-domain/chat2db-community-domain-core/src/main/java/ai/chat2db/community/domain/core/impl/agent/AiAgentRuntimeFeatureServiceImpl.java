package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.feature.AgentRuntimeEnableResult;
import ai.chat2db.community.domain.api.model.agent.feature.AgentRuntimeFeatureState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.agent.PiRuntimeInstallTaskSpec;
import ai.chat2db.community.domain.api.service.agent.IAgentFeatureFlagStorage;
import ai.chat2db.community.domain.api.service.agent.IAiAgentRuntimeFeatureService;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEnvironmentChecker;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeInstallation;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class AiAgentRuntimeFeatureServiceImpl implements IAiAgentRuntimeFeatureService {

    private final IAgentFeatureFlagStorage flagStorage;
    private final IAgentRuntimeEnvironmentChecker environmentChecker;
    private final IAgentRuntimeInstallation installer;
    private final TaskService taskService;
    private Long installingTaskId;

    public AiAgentRuntimeFeatureServiceImpl(
            IAgentFeatureFlagStorage flagStorage,
            IAgentRuntimeEnvironmentChecker environmentChecker,
            IAgentRuntimeInstallation installer) {
        this(flagStorage, environmentChecker, installer, null);
    }

    public AiAgentRuntimeFeatureServiceImpl(
            IAgentFeatureFlagStorage flagStorage,
            IAgentRuntimeEnvironmentChecker environmentChecker,
            IAgentRuntimeInstallation installer,
            TaskService taskService) {
        this.flagStorage = flagStorage;
        this.environmentChecker = environmentChecker;
        this.installer = installer;
        this.taskService = taskService;
    }

    @Override
    public AgentRuntimeType runtimeType() {
        return AgentRuntimeType.PI;
    }

    @Override
    public AgentRuntimeFeatureState check(AgentRuntimeEnvironmentRequest environment) {
        AgentRuntimeEnvironmentReport report = environmentChecker.inspect(environment);
        return new AgentRuntimeFeatureState(
                runtimeType(), flagStorage.isEnabled(runtimeType()), report.isUsable(), report);
    }

    @Override
    public synchronized AgentRuntimeFeatureState enable(AgentRuntimeEnvironmentRequest environment) {
        try {
            installer.install(environment);
            AgentRuntimeEnvironmentReport report = environmentChecker.inspect(environment);
            if (!report.isUsable()) {
                flagStorage.setEnabled(runtimeType(), false);
                return new AgentRuntimeFeatureState(runtimeType(), false, false, report);
            }
            flagStorage.setEnabled(runtimeType(), true);
            return new AgentRuntimeFeatureState(runtimeType(), true, true, report);
        // impl-contract: fallback - installation failure keeps the feature disabled and reports BLOCKED to the caller.
        } catch (IOException | RuntimeException error) {
            flagStorage.setEnabled(runtimeType(), false);
            AgentRuntimeEnvironmentReport report = new AgentRuntimeEnvironmentReport(
                    runtimeType(), AgentRuntimeEnvironmentStatus.BLOCKED, null,
                    environment.operatingSystem(), environment.architecture(), List.of("INSTALL_FAILED"),
                    Map.of("reason", java.util.Objects.toString(
                            error.getMessage(), error.getClass().getSimpleName())), LocalDateTime.now());
            return new AgentRuntimeFeatureState(runtimeType(), false, false, report);
        }
    }

    @Override
    public synchronized AgentRuntimeEnableResult enableAsync(AgentRuntimeEnvironmentRequest environment) {
        AgentRuntimeFeatureState current = check(environment);
        if (current.enabled() && current.installed()) {
            return new AgentRuntimeEnableResult(current, null);
        }
        if (installingTaskId != null) {
            Task task = taskService == null ? null : taskService.get(installingTaskId);
            if (task != null && "SUCCESS".equals(task.getStatus())) {
                flagStorage.setEnabled(runtimeType(), true);
                installingTaskId = null;
                return new AgentRuntimeEnableResult(check(environment), null);
            }
            if (task != null && ("FAILED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus()))) {
                flagStorage.setEnabled(runtimeType(), false);
                installingTaskId = null;
                return new AgentRuntimeEnableResult(check(environment), null);
            }
            return new AgentRuntimeEnableResult(current, installingTaskId);
        }
        if (taskService == null) {
            return new AgentRuntimeEnableResult(enable(environment), null);
        }
        if (installingTaskId == null) {
            installingTaskId = findActiveInstallTask();
            if (installingTaskId != null) {
                return new AgentRuntimeEnableResult(current, installingTaskId);
            }
        }
        installingTaskId = taskService.submitImport(new PiRuntimeInstallTaskSpec(environment));
        return new AgentRuntimeEnableResult(current, installingTaskId);
    }

    @Override
    public synchronized AgentRuntimeFeatureState disable(AgentRuntimeEnvironmentRequest environment) {
        flagStorage.setEnabled(runtimeType(), false);
        return check(environment);
    }

    private Long findActiveInstallTask() {
        for (String status : new String[] {"PENDING", "RUNNING"}) {
            TaskQuery query = new TaskQuery();
            query.setStatus(status);
            query.setPageNo(1);
            query.setPageSize(50);
            var page = taskService.list(query);
            for (Task task : page.getData()) {
                if ("PI_RUNTIME_INSTALL".equals(task.getType())) {
                    return task.getId();
                }
            }
        }
        return null;
    }

}
