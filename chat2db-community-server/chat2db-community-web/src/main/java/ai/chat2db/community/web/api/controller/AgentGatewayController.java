package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentDeliveryCommand;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannel;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannelCredential;
import ai.chat2db.community.domain.api.model.agent.AgentInboundMessageResult;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayChannelCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayDeliveryClaimRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayDeliveryReceiptRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayInboundRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentGatewayBridgeService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/gateway")
public class AgentGatewayController {

    public static final String GATEWAY_TOKEN_HEADER = "X-Chat2DB-Agent-Gateway-Token";

    private final IAgentGatewayBridgeService gatewayService;
    private final IIdentityService identityService;

    public AgentGatewayController(IAgentGatewayBridgeService gatewayService, IIdentityService identityService) {
        this.gatewayService = gatewayService;
        this.identityService = identityService;
    }

    @PostMapping("/channels")
    public DataResult<AgentGatewayChannelCredential> createChannel(
            @RequestBody AgentGatewayChannelCreateRequest request) {
        if (request == null) throw new IllegalArgumentException("gateway channel request is required");
        request.setCreatedBy(identityService.currentUserId());
        return DataResult.of(gatewayService.createChannel(request));
    }

    @GetMapping("/channels")
    public ListResult<AgentGatewayChannel> listChannels() {
        return ListResult.of(gatewayService.listChannels(identityService.currentUserId()));
    }

    @PostMapping("/channels/{channelId}/inbound")
    public DataResult<AgentInboundMessageResult> acceptInbound(
            @PathVariable String channelId,
            @RequestHeader(GATEWAY_TOKEN_HEADER) String gatewayToken,
            @RequestBody AgentGatewayInboundRequest request) {
        return DataResult.of(gatewayService.acceptInbound(channelId, gatewayToken, request));
    }

    @PostMapping("/channels/{channelId}/deliveries/claim")
    public ListResult<AgentDeliveryCommand> claimDeliveries(
            @PathVariable String channelId,
            @RequestHeader(GATEWAY_TOKEN_HEADER) String gatewayToken,
            @RequestBody(required = false) AgentGatewayDeliveryClaimRequest request) {
        int limit = request == null || request.getLimit() == null ? 10 : request.getLimit();
        return ListResult.of(gatewayService.claimDeliveries(channelId, gatewayToken, limit));
    }

    @PostMapping("/channels/{channelId}/deliveries/{deliveryId}/receipt")
    public DataResult<AgentDeliveryCommand> acknowledgeDelivery(
            @PathVariable String channelId,
            @PathVariable String deliveryId,
            @RequestHeader(GATEWAY_TOKEN_HEADER) String gatewayToken,
            @RequestBody AgentGatewayDeliveryReceiptRequest request) {
        return DataResult.of(gatewayService.acknowledgeDelivery(
                channelId, gatewayToken, deliveryId, request));
    }
}
