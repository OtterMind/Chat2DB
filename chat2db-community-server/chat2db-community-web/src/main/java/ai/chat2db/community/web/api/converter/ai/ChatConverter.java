package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.domain.api.model.request.ai.AiModelConfigSaveRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiBusinessContextBuildRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiSelectedKnowledge;
import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import ai.chat2db.community.web.api.model.request.ai.ModelConfigSaveRequest;
import ai.chat2db.community.web.api.model.request.ai.ModelConfigTestRequest;
import ai.chat2db.community.web.api.model.response.ai.AiAttachmentResponse;
import ai.chat2db.community.web.api.model.response.ai.AiChatMessageResponse;
import ai.chat2db.community.web.api.model.response.ai.AiChatSessionResponse;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;


@Mapper(componentModel = "spring")
public abstract class ChatConverter {

    protected Float float2double(Double value) {
        return value == null ? null : value.floatValue();
    }

    public AiChatRuntimeResolveRequest toRuntimeResolveParam(ChatRequest request) {
        AiChatRuntimeResolveRequest param = new AiChatRuntimeResolveRequest();
        if (request == null) {
            return param;
        }
        param.setModelConfigId(request.getModelConfigId());
        param.setProvider(request.getProvider() == null ? null : request.getProvider().name());
        param.setModel(request.getModel());
        param.setApiKey(request.getApiKey());
        param.setBaseUrl(request.getBaseUrl());
        param.setProjectId(request.getProjectId());
        param.setLocation(request.getLocation());
        param.setTemperature(request.getTemperature());
        param.setMaxTokens(request.getMaxTokens());
        return param;
    }

    public AiBusinessContextBuildRequest toBusinessContextParam(ChatRequest request) {
        AiBusinessContextBuildRequest param = new AiBusinessContextBuildRequest();
        if (request == null) {
            return param;
        }
        param.setDataSourceId(request.getDataSourceId());
        param.setDatabaseName(request.getDatabaseName());
        param.setSchemaName(request.getSchemaName());
        param.setSelectedKnowledge(request.getSelectedKnowledge() == null
                ? List.of()
                : request.getSelectedKnowledge().stream().map(source -> {
                    AiSelectedKnowledge selected = new AiSelectedKnowledge();
                    selected.setId(source.getId());
                    selected.setType(source.getType());
                    return selected;
                }).toList());
        return param;
    }

    public AiModelConfigSaveRequest toModelConfigParam(ModelConfigSaveRequest request) {
        AiModelConfigSaveRequest param =
                new AiModelConfigSaveRequest();
        if (request == null) {
            return param;
        }
        param.setId(request.getId());
        param.setName(request.getName());
        param.setProvider(request.getProvider() == null ? null : request.getProvider().name());
        param.setModel(request.getModel());
        param.setApiKey(request.getApiKey());
        param.setBaseUrl(request.getBaseUrl());
        param.setProjectId(request.getProjectId());
        param.setLocation(request.getLocation());
        param.setTemperature(request.getTemperature());
        param.setMaxTokens(request.getMaxTokens());
        param.setEnabled(request.getEnabled());
        param.setDefaultConfig(request.getDefaultConfig());
        return param;
    }

    public AiModelConfigSaveRequest toModelConfigParam(ModelConfigTestRequest request) {
        AiModelConfigSaveRequest param = new AiModelConfigSaveRequest();
        if (request == null) {
            return param;
        }
        param.setId(request.getId());
        param.setProvider(request.getProvider() == null ? null : request.getProvider().name());
        param.setModel(request.getModel());
        param.setApiKey(request.getApiKey());
        param.setBaseUrl(request.getBaseUrl());
        param.setProjectId(request.getProjectId());
        param.setLocation(request.getLocation());
        param.setTemperature(request.getTemperature());
        param.setMaxTokens(request.getMaxTokens());
        return param;
    }

    public abstract AiChatSessionResponse session2response(AiChatSession session);

    public abstract List<AiChatSessionResponse> session2response(List<AiChatSession> sessions);

    public abstract AiChatMessageResponse message2response(AiChatMessage message);

    public abstract List<AiChatMessageResponse> message2response(List<AiChatMessage> messages);

    public abstract AiAttachmentResponse attachment2response(ChatAttachment attachment);

    public Message roleContent2message(String role, String content) {
        String normalizedRole = StringUtils.trimToEmpty(role).toLowerCase();
        if ("user".equals(normalizedRole)) {
            return new UserMessage(content);
        }
        if ("assistant".equals(normalizedRole)) {
            return new AssistantMessage(content);
        }
        if ("system".equals(normalizedRole)) {
            return new SystemMessage(content);
        }
        return null;
    }
}
