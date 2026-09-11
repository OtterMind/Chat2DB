package ai.chat2db.community.domain.core.converter;

import ai.chat2db.community.domain.api.model.ai.AiModelConfig;
import ai.chat2db.community.domain.api.model.ai.AiModelConfigResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class AiModelConfigConverter {

    public AiModelConfigResponse toMaskedResponse(AiModelConfig config) {
        if (config == null) {
            return null;
        }
        AiModelConfigResponse response = new AiModelConfigResponse();
        response.setId(config.getId());
        response.setName(config.getName());
        response.setProvider(config.getProvider());
        response.setModel(config.getModel());
        response.setAgentApi(config.getAgentApi());
        response.setBaseUrl(config.getBaseUrl());
        response.setProjectId(config.getProjectId());
        response.setLocation(config.getLocation());
        response.setTemperature(config.getTemperature());
        response.setMaxTokens(config.getMaxTokens());
        response.setEnabled(config.getEnabled());
        response.setDefaultConfig(config.getDefaultConfig());
        response.setGmtModified(config.getGmtModified());
        response.setHasApiKey(StringUtils.isNotBlank(config.getApiKey()));
        response.setApiKeyMasked(apiKey2masked(config.getApiKey()));
        return response;
    }

    private String apiKey2masked(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
