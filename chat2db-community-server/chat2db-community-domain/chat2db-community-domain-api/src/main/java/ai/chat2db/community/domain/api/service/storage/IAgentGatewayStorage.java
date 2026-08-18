package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.domain.api.model.agent.AgentDeliveryCommand;
import ai.chat2db.community.domain.api.model.agent.AgentExternalConversationBinding;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannel;
import ai.chat2db.community.domain.api.model.agent.AgentInboundMessage;

import java.util.Date;
import java.util.List;

public interface IAgentGatewayStorage {
    AgentGatewayChannel createGatewayChannel(AgentGatewayChannel channel, String tokenHash);
    AgentGatewayChannel getGatewayChannel(String channelId);
    List<AgentGatewayChannel> listGatewayChannels(Long ownerId);
    boolean matchesGatewayToken(String channelId, String tokenHash);
    AgentExternalConversationBinding getConversationBinding(String bindingId);
    AgentExternalConversationBinding getConversationBinding(String channelId, String chatId, String threadId);
    AgentExternalConversationBinding createConversationBinding(AgentExternalConversationBinding binding);
    AgentInboundMessage getInboundMessage(String channelId, String idempotencyKey);
    AgentInboundMessage createInboundMessage(AgentInboundMessage message);
    AgentInboundMessage attachInboundTask(String messageId, String taskId, long expectedRevision);
    List<AgentInboundMessage> listInboundMessagesAwaitingDelivery(String channelId);
    AgentDeliveryCommand createOrGetDelivery(AgentDeliveryCommand command);
    List<AgentDeliveryCommand> claimDeliveries(String channelId, Date now, Date leaseExpiresAt, int limit);
    AgentDeliveryCommand getDelivery(String deliveryId);
    AgentDeliveryCommand updateDelivery(AgentDeliveryCommand command, long expectedRevision);
}
