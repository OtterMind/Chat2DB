package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentDeliveryCommand;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannel;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannelCredential;
import ai.chat2db.community.domain.api.model.agent.AgentInboundMessageResult;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayChannelCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayDeliveryReceiptRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayInboundRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentGatewayBridgeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentGatewayControllerTest {

    @Test
    void injectsOwnerForChannelManagementAndForwardsGatewayCredential() {
        RecordingGatewayService service = new RecordingGatewayService();
        AgentGatewayController controller = new AgentGatewayController(service, () -> 42L);
        AgentGatewayChannelCreateRequest create = new AgentGatewayChannelCreateRequest();
        controller.createChannel(create);
        controller.listChannels();

        AgentGatewayInboundRequest inbound = new AgentGatewayInboundRequest();
        controller.acceptInbound("channel-1", "gateway-token", inbound);
        controller.claimDeliveries("channel-1", "gateway-token", null);
        AgentGatewayDeliveryReceiptRequest receipt = new AgentGatewayDeliveryReceiptRequest();
        controller.acknowledgeDelivery("channel-1", "delivery-1", "gateway-token", receipt);

        assertEquals(42L, create.getCreatedBy());
        assertEquals(42L, service.listOwner);
        assertEquals("channel-1", service.channelId);
        assertEquals("gateway-token", service.gatewayToken);
        assertEquals("delivery-1", service.deliveryId);
        assertEquals(10, service.claimLimit);
    }

    private static final class RecordingGatewayService implements IAgentGatewayBridgeService {
        private Long listOwner;
        private String channelId;
        private String gatewayToken;
        private String deliveryId;
        private int claimLimit;

        @Override public AgentGatewayChannelCredential createChannel(AgentGatewayChannelCreateRequest request) {
            AgentGatewayChannelCredential result = new AgentGatewayChannelCredential();
            result.setChannel(new AgentGatewayChannel()); return result;
        }
        @Override public List<AgentGatewayChannel> listChannels(Long ownerId) { listOwner = ownerId; return List.of(); }
        @Override public AgentInboundMessageResult acceptInbound(String channelId, String gatewayToken,
                                                                 AgentGatewayInboundRequest request) {
            this.channelId = channelId; this.gatewayToken = gatewayToken; return new AgentInboundMessageResult();
        }
        @Override public List<AgentDeliveryCommand> claimDeliveries(String channelId, String gatewayToken, int limit) {
            this.channelId = channelId; this.gatewayToken = gatewayToken; this.claimLimit = limit; return List.of();
        }
        @Override public AgentDeliveryCommand acknowledgeDelivery(String channelId, String gatewayToken,
                                                                  String deliveryId,
                                                                  AgentGatewayDeliveryReceiptRequest request) {
            this.channelId = channelId; this.gatewayToken = gatewayToken; this.deliveryId = deliveryId;
            return new AgentDeliveryCommand();
        }
    }
}
