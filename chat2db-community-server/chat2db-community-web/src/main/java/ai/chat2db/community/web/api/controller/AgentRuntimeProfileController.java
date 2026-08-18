package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeOption;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeProfileCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeProfileUpdateRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeControlService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/agent")
public class AgentRuntimeProfileController {

    private final IAgentRuntimeControlService runtimeService;
    private final IIdentityService identityService;

    public AgentRuntimeProfileController(IAgentRuntimeControlService runtimeService, IIdentityService identityService) {
        this.runtimeService = runtimeService;
        this.identityService = identityService;
    }

    @PostMapping("/runtime-profiles")
    public DataResult<AgentRuntimeProfile> createProfile(
            @RequestBody AgentRuntimeProfileCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("runtime profile request is required");
        }
        request.setCreatedBy(identityService.currentUserId());
        return DataResult.of(runtimeService.createProfile(request));
    }

    @PostMapping("/runtime-profiles/{profileId}")
    public DataResult<AgentRuntimeProfile> updateProfile(
            @PathVariable String profileId, @RequestBody AgentRuntimeProfileUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("runtime profile update request is required");
        }
        requireOwner(runtimeService.getProfile(profileId));
        request.setProfileId(profileId);
        return DataResult.of(runtimeService.updateProfile(request));
    }

    @GetMapping("/runtime-profiles/{profileId}")
    public DataResult<AgentRuntimeProfile> getProfile(@PathVariable String profileId) {
        AgentRuntimeProfile profile = runtimeService.getProfile(profileId);
        requireOwner(profile);
        return DataResult.of(profile);
    }

    @GetMapping("/runtime-profiles")
    public ListResult<AgentRuntimeProfile> listProfiles() {
        Long userId = identityService.currentUserId();
        return ListResult.of(runtimeService.listProfiles().stream()
                .filter(profile -> Objects.equals(profile.getCreatedBy(), userId))
                .toList());
    }

    @GetMapping("/runtime-instances")
    public ListResult<AgentRuntimeInstance> listInstances() {
        identityService.currentUserId();
        return ListResult.of(runtimeService.listInstances());
    }

    @GetMapping("/runtime-options")
    public ListResult<AgentRuntimeOption> listRuntimeOptions() {
        return ListResult.of(runtimeService.listRuntimeOptions(identityService.currentUserId()));
    }

    @GetMapping("/runtime-instances/{instanceId}")
    public DataResult<AgentRuntimeInstance> getInstance(@PathVariable String instanceId) {
        identityService.currentUserId();
        return DataResult.of(runtimeService.getInstance(instanceId));
    }

    private void requireOwner(AgentRuntimeProfile profile) {
        if (!Objects.equals(profile.getCreatedBy(), identityService.currentUserId())) {
            throw new SecurityException("runtime profile does not belong to current user");
        }
    }
}
