package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.feature.AgentFeatureState;
import ai.chat2db.community.domain.api.model.agent.feature.AgentRuntimeEnableResult;
import ai.chat2db.community.domain.api.model.agent.feature.AgentRuntimeFeatureState;
import ai.chat2db.community.domain.api.service.agent.AgentFeatureService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentRuntimeFeatureService;
import ai.chat2db.community.tools.enums.agent.AgentFeature;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.web.api.adapter.agent.AgentHostEnvironmentProvider;
import ai.chat2db.community.web.api.model.request.agent.AgentRuntimeEnableRequest;
import jakarta.validation.Valid;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/ai/features")
public class AgentFeatureController {

    private final Map<AgentRuntimeType, IAiAgentRuntimeFeatureService> services;
    private final Map<AgentFeature, AgentFeatureService> featureServices;
    private final AgentHostEnvironmentProvider environmentProvider;

    public AgentFeatureController(
            List<IAiAgentRuntimeFeatureService> services,
            List<AgentFeatureService> featureServices,
            AgentHostEnvironmentProvider environmentProvider) {
        Map<AgentRuntimeType, IAiAgentRuntimeFeatureService> indexed = new EnumMap<>(AgentRuntimeType.class);
        for (IAiAgentRuntimeFeatureService service : services) {
            if (indexed.putIfAbsent(service.runtimeType(), service) != null) {
                throw new IllegalStateException("Duplicate agent runtime feature service: " + service.runtimeType());
            }
        }
        this.services = Map.copyOf(indexed);
        Map<AgentFeature, AgentFeatureService> indexedFeatures = new EnumMap<>(AgentFeature.class);
        for (AgentFeatureService service : featureServices) {
            if (indexedFeatures.putIfAbsent(service.feature(), service) != null) {
                throw new IllegalStateException("Duplicate agent feature service: " + service.feature());
            }
        }
        this.featureServices = Map.copyOf(indexedFeatures);
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
    public DataResult<AgentRuntimeEnableResult> enablePi(
            @RequestBody @Valid AgentRuntimeEnableRequest request) {
        return DataResult.of(require(AgentRuntimeType.PI).enableAsync(environmentProvider.current()));
    }

    @PostMapping("/pi/disable")
    public DataResult<AgentRuntimeFeatureState> disablePi() {
        return DataResult.of(require(AgentRuntimeType.PI).disable(environmentProvider.current()));
    }

    @PostMapping("/bash/check")
    public DataResult<AgentFeatureState> checkBash() {
        return DataResult.of(require(AgentFeature.BASH).check());
    }

    @PostMapping("/bash/enable")
    public DataResult<AgentFeatureState> enableBash(
            @RequestBody @Valid AgentRuntimeEnableRequest request) {
        return DataResult.of(require(AgentFeature.BASH).enable());
    }

    @PostMapping("/bash/disable")
    public DataResult<AgentFeatureState> disableBash() {
        return DataResult.of(require(AgentFeature.BASH).disable());
    }

    private IAiAgentRuntimeFeatureService require(AgentRuntimeType runtimeType) {
        IAiAgentRuntimeFeatureService service = services.get(runtimeType);
        if (service == null) {
            throw new IllegalStateException("Agent runtime is unavailable: " + runtimeType);
        }
        return service;
    }

    private AgentFeatureService require(AgentFeature feature) {
        AgentFeatureService service = featureServices.get(feature);
        if (service == null) {
            throw new IllegalStateException("Agent feature is unavailable: " + feature);
        }
        return service;
    }
}
