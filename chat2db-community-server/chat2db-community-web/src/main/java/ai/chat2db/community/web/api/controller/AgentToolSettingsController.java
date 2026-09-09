package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentShellSettings;
import ai.chat2db.community.domain.api.model.agent.AgentToolState;
import ai.chat2db.community.domain.api.service.agent.AgentShellSettingsService;
import ai.chat2db.community.domain.api.service.agent.AgentToolAccessService;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v3/ai/features")
public class AgentToolSettingsController {
    private final AgentToolAccessService tools;
    private final List<AgentShellSettingsService> settings;

    public AgentToolSettingsController(AgentToolAccessService tools, List<AgentShellSettingsService> settings) {
        this.tools = tools;
        this.settings = settings;
    }

    @GetMapping("/tools")
    public ListResult<AgentToolState> listTools() {
        return ListResult.of(tools.listTools());
    }

    @GetMapping("/bash/settings")
    public DataResult<AgentShellSettings> getSettings() {
        return DataResult.of(settings().get());
    }

    @PostMapping("/bash/settings")
    public DataResult<AgentShellSettings> updateSettings(@RequestBody @Valid SettingsRequest request) {
        return DataResult.of(settings().update(request.workingDirectory()));
    }

    private AgentShellSettingsService settings() {
        if (settings.isEmpty()) throw new AgentRuntimeUnavailableException("PI", "Local shell settings are unavailable");
        return settings.get(0);
    }

    public record SettingsRequest(@NotNull String workingDirectory) { }
}
