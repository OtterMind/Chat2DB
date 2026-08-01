package ai.chat2db.community.start.ai.secret;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.AiModelConfigResponse;
import ai.chat2db.community.domain.api.model.request.ai.AiModelConfigSaveRequest;
import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import ai.chat2db.community.tools.security.secretimport.ImportedApiKeyConfig;
import ai.chat2db.community.tools.security.secretimport.MaskedConfigAcknowledgement;
import ai.chat2db.community.tools.security.secretimport.SecretImportModelConfigPort;

import java.util.Objects;

/**
 * Writes decrypted legacy API-key records through the existing Community
 * model-config service, whose disk representation is AES-GCM encrypted.
 */
public final class AiModelConfigSecretImportPort implements SecretImportModelConfigPort {

    private final IAiModelConfigService modelConfigService;

    public AiModelConfigSecretImportPort(IAiModelConfigService modelConfigService) {
        this.modelConfigService = Objects.requireNonNull(modelConfigService, "modelConfigService");
    }

    @Override
    public MaskedConfigAcknowledgement writeAndReadback(ImportedApiKeyConfig config) {
        Objects.requireNonNull(config, "config");
        AiProviderEnum provider = AiProviderEnum.from(config.getProvider());
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported AI provider");
        }

        AiModelConfigSaveRequest request = new AiModelConfigSaveRequest();
        request.setId(config.getId());
        request.setName(config.getName());
        request.setProvider(provider.name());
        request.setModel(config.getModel());
        request.setApiKey(config.getApiKey());
        request.setBaseUrl(config.getBaseUrl());
        request.setProjectId(config.getProjectId());
        request.setLocation(config.getLocation());
        request.setTemperature(config.getTemperature());
        request.setMaxTokens(config.getMaxTokens());
        request.setEnabled(config.getEnabled());
        request.setDefaultConfig(config.getDefaultConfig());

        boolean confirmDefault = Boolean.TRUE.equals(config.getDefaultConfig());
        AiModelConfigResponse written = modelConfigService.importLegacyCurrentUserConfig(request, confirmDefault);
        AiModelConfigResponse readback = modelConfigService.listCurrentUserConfigs().stream()
                .filter(item -> Objects.equals(written.getId(), item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Imported model configuration not found on readback"));
        if (!Boolean.TRUE.equals(readback.getHasApiKey())) {
            throw new IllegalStateException("Imported model configuration has no persisted API key");
        }

        MaskedConfigAcknowledgement acknowledgement = new MaskedConfigAcknowledgement();
        acknowledgement.setConfigId(readback.getId());
        acknowledgement.setName(readback.getName());
        acknowledgement.setProvider(readback.getProvider());
        acknowledgement.setModel(readback.getModel());
        acknowledgement.setHasApiKey(Boolean.TRUE.equals(readback.getHasApiKey()));
        acknowledgement.setDefaultConfig(Boolean.TRUE.equals(readback.getDefaultConfig()));
        return acknowledgement;
    }
}
