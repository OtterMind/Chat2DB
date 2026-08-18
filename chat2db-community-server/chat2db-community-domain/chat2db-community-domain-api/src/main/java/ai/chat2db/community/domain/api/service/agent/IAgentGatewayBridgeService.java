package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentDeliveryCommand;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannel;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannelCredential;
import ai.chat2db.community.domain.api.model.agent.AgentInboundMessageResult;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayChannelCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayDeliveryReceiptRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentGatewayInboundRequest;

import java.util.List;

public interface IAgentGatewayBridgeService {
    AgentGatewayChannelCredential createChannel(AgentGatewayChannelCreateRequest request);
    List<AgentGatewayChannel> listChannels(Long ownerId);
    AgentInboundMessageResult acceptInbound(String channelId, String gatewayToken,
                                            AgentGatewayInboundRequest request);
    List<AgentDeliveryCommand> claimDeliveries(String channelId, String gatewayToken, int limit);
    AgentDeliveryCommand acknowledgeDelivery(String channelId, String gatewayToken, String deliveryId,
                                              AgentGatewayDeliveryReceiptRequest request);
}
