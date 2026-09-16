package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.feature.AgentWorkspaceSettings;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolState;
import ai.chat2db.community.domain.api.service.agent.AgentToolAccessService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentWorkspaceService;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.web.api.config.console.DesktopBridgeRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
@RequestMapping("/api/v3/ai/features")
public class AgentToolSettingsController {
    private final AgentToolAccessService tools;
    private final List<IAiAgentWorkspaceService> settings;

    public AgentToolSettingsController(AgentToolAccessService tools, List<IAiAgentWorkspaceService> settings) {
        this.tools = tools;
        this.settings = settings;
    }

    @GetMapping("/tools")
    public ListResult<AgentToolState> listTools() {
        return ListResult.of(tools.listTools());
    }

    @GetMapping({"/tools/settings", "/bash/settings"})
    public DataResult<AgentWorkspaceSettings> getSettings() {
        return DataResult.of(settings().get());
    }

    @PostMapping({"/tools/settings", "/bash/settings"})
    public DataResult<AgentWorkspaceSettings> updateSettings(@RequestBody @Valid SettingsRequest request) {
        return DataResult.of(settings().update(request.workingDirectory()));
    }

    @PostMapping("/tools/select-directory")
    public DataResult<String> selectDirectory() {
        if (!DesktopBridgeRequestContext.isActive()) {
            var attributes = RequestContextHolder.getRequestAttributes();
            String remote = attributes instanceof ServletRequestAttributes servlet
                    ? servlet.getRequest().getRemoteAddr() : null;
            if (!("127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote))) {
                throw new SecurityException("Directory selection is available only on the local computer");
            }
        }
        return DataResult.of(settings().selectDirectory());
    }

    @PostMapping("/tools/{toolName}/enabled")
    public DataResult<AgentToolState> setToolEnabled(@PathVariable String toolName,
            @RequestBody @Valid ToolEnabledRequest request) {
        settings().setToolEnabled(toolName, request.enabled());
        return DataResult.of(tools.listTools().stream().filter(tool -> tool.name().equals(toolName)).findFirst().orElseThrow());
    }

    private IAiAgentWorkspaceService settings() {
        if (settings.isEmpty()) throw new AgentRuntimeUnavailableException("PI", "Local workspace settings are unavailable");
        return settings.get(0);
    }

    public record ToolEnabledRequest(@NotNull Boolean enabled) { }

    public record SettingsRequest(@NotNull String workingDirectory) { }
}
