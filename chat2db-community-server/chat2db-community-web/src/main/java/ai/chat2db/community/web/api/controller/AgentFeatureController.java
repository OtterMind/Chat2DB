package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeFeatureState;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeFeatureService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.web.api.adapter.agent.AgentHostEnvironmentProvider;
import ai.chat2db.community.web.api.model.request.agent.AgentRuntimeEnableRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v3/ai/features")
public class AgentFeatureController {

    private final Map<AgentRuntimeType, AgentRuntimeFeatureService> services;
    private final AgentHostEnvironmentProvider environmentProvider;

    public AgentFeatureController(
            List<AgentRuntimeFeatureService> services,
            AgentHostEnvironmentProvider environmentProvider) {
        Map<AgentRuntimeType, AgentRuntimeFeatureService> indexed = new EnumMap<>(AgentRuntimeType.class);
        for (AgentRuntimeFeatureService service : services) {
            if (indexed.putIfAbsent(service.runtimeType(), service) != null) {
                throw new IllegalStateException("Duplicate agent runtime feature service: " + service.runtimeType());
            }
        }
        this.services = Map.copyOf(indexed);
        this.environmentProvider = environmentProvider;
    }

    @GetMapping
    public ListResult<AgentRuntimeFeatureState> list() {
        return ListResult.of(services.values().stream()
                .map(service -> service.check(environmentProvider.current()))
                .toList());
    }

    @PostMapping("/pi/check")
    public DataResult<AgentRuntimeFeatureState> checkPi() {
        return DataResult.of(require(AgentRuntimeType.PI).check(environmentProvider.current()));
    }

    @PostMapping("/pi/enable")
    public DataResult<AgentRuntimeFeatureState> enablePi(
            @RequestBody @Valid AgentRuntimeEnableRequest request) {
        return DataResult.of(require(AgentRuntimeType.PI).enable(environmentProvider.current()));
    }

    @PostMapping("/pi/disable")
    public DataResult<AgentRuntimeFeatureState> disablePi() {
        return DataResult.of(require(AgentRuntimeType.PI).disable(environmentProvider.current()));
    }

    private AgentRuntimeFeatureService require(AgentRuntimeType runtimeType) {
        AgentRuntimeFeatureService service = services.get(runtimeType);
        if (service == null) {
            throw new IllegalStateException("Agent runtime is unavailable: " + runtimeType);
        }
        return service;
    }
}
