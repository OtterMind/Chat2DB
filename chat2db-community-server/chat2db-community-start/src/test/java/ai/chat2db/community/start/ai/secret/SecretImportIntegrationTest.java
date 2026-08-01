package ai.chat2db.community.start.ai.secret;

import ai.chat2db.community.domain.api.model.ai.AiModelCatalogItem;
import ai.chat2db.community.domain.api.model.ai.AiModelConfigResponse;
import ai.chat2db.community.domain.api.model.ai.AiModelOptionItem;
import ai.chat2db.community.domain.api.model.ai.AiRuntimeModel;
import ai.chat2db.community.domain.api.model.ai.ModelConfigTestResponse;
import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiModelConfigSaveRequest;
import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import ai.chat2db.community.tools.security.secretimport.ImportedApiKeyConfig;
import ai.chat2db.community.tools.security.secretimport.MaskedConfigAcknowledgement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretImportIntegrationTest {

    @Test
    void runtimeGateAllowsOnlyExplicitlyEnabledPackagedCommunityDesktopGui() {
        assertTrue(SecretImportRuntimeGate.isEnabled(true, true, true, true, true));
        assertFalse(SecretImportRuntimeGate.isEnabled(false, true, true, true, true));
        assertFalse(SecretImportRuntimeGate.isEnabled(true, false, true, true, true));
        assertFalse(SecretImportRuntimeGate.isEnabled(true, true, false, true, true));
        assertFalse(SecretImportRuntimeGate.isEnabled(true, true, true, false, true));
        assertFalse(SecretImportRuntimeGate.isEnabled(true, true, true, true, false));
    }

    @Test
    void portUsesLegacyImportPolicyWithoutReturningAnyKeyFragment() {
        RecordingModelConfigService service = new RecordingModelConfigService();
        AiModelConfigSecretImportPort port = new AiModelConfigSecretImportPort(service);
        ImportedApiKeyConfig config = new ImportedApiKeyConfig();
        config.setId("legacy-1");
        config.setName("Legacy OpenAI");
        config.setProvider("OPENAI");
        config.setModel("gpt-test");
        config.setApiKey("sk-canary-secret-1234567890");
        config.setDefaultConfig(Boolean.TRUE);
        config.setEnabled(Boolean.TRUE);

        MaskedConfigAcknowledgement acknowledgement = port.writeAndReadback(config);

        assertTrue(service.confirmDefault);
        assertEquals("sk-canary-secret-1234567890", service.request.getApiKey());
        assertEquals("legacy-1", acknowledgement.getConfigId());
        var safeResult = ai.chat2db.community.tools.security.secretimport.SecretImportItemResult
                .succeeded("attempt-1", "item-1", acknowledgement).toSafeMap();
        assertFalse(safeResult.containsKey("apiKeyMasked"));
        assertFalse(safeResult.toString().contains("sk-c"));
        assertFalse(safeResult.toString().contains("7890"));
    }

    private static final class RecordingModelConfigService implements IAiModelConfigService {
        private AiModelConfigSaveRequest request;
        private boolean confirmDefault;
        private AiModelConfigResponse response;

        @Override
        public AiModelConfigResponse importLegacyCurrentUserConfig(
                AiModelConfigSaveRequest request,
                boolean confirmDefault) {
            this.request = request;
            this.confirmDefault = confirmDefault;
            response = new AiModelConfigResponse();
            response.setId(request.getId());
            response.setName(request.getName());
            response.setProvider(request.getProvider());
            response.setModel(request.getModel());
            response.setHasApiKey(Boolean.TRUE);
            response.setApiKeyMasked("sk-c****7890");
            response.setDefaultConfig(confirmDefault);
            return response;
        }

        @Override
        public List<AiModelConfigResponse> listCurrentUserConfigs() {
            return response == null ? List.of() : List.of(response);
        }

        @Override
        public List<AiModelCatalogItem> listPresetModels() {
            return List.of();
        }

        @Override
        public List<AiModelOptionItem> listModelOptions() {
            return List.of();
        }

        @Override
        public AiModelConfigResponse saveCurrentUserConfig(AiModelConfigSaveRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteCurrentUserConfig(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelConfigTestResponse testModelConfig(AiModelConfigSaveRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AiRuntimeModel resolveRuntimeModel(AiChatRuntimeResolveRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
