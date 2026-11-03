package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.AiModelCatalogItem;
import ai.chat2db.community.domain.api.model.ai.AiModelOptionItem;
import ai.chat2db.community.domain.api.model.request.ai.AiModelConfigSaveRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
import ai.chat2db.community.domain.core.converter.AiModelConfigConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelConfigServiceImplPresetCatalogTest {

    private static final long USER_ID = 42L;

    @TempDir
    Path tempDirectory;

    @Test
    void presetCatalogIncludesMinimaxModels() {
        AiModelConfigServiceImpl service = service();

        List<AiModelCatalogItem> catalog = service.listPresetModels();
        AiModelCatalogItem minimax = catalog.stream()
                .filter(item -> AiProviderEnum.MINIMAX.name().equals(item.getProvider()))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("MiniMax-M3.1-Flash", "MiniMax-M3"), minimax.getModels());
    }

    @Test
    void modelOptionsIncludeMinimaxPresets() {
        AiModelConfigServiceImpl service = service();

        List<AiModelOptionItem> options = service.listModelOptions();

        assertEquals(List.of("MiniMax-M3.1-Flash", "MiniMax-M3"), options.stream()
                .filter(option -> AiProviderEnum.MINIMAX.name().equals(option.getProvider()))
                .map(AiModelOptionItem::getModel).toList());
    }

    @Test
    void savedLegacyAndCustomDefaultsSurviveCatalogRefresh() {
        for (String model : List.of("MiniMax-M2.7", "custom-minimax")) {
            AiModelConfigServiceImpl service = service();
            AiModelConfigSaveRequest save = new AiModelConfigSaveRequest();
            save.setProvider("MINIMAX");
            save.setName(model);
            save.setModel(model);
            save.setApiKey("test-key");
            save.setDefaultConfig(true);
            service.saveCurrentUserConfig(save);

            AiModelConfigServiceImpl reloaded = service();
            reloaded.init();
            AiModelOptionItem preferred = reloaded.listModelOptions().get(0);
            assertEquals(model, preferred.getModel());
            assertTrue(preferred.getDefaultOption());
            assertTrue(preferred.getCustomOption());
            assertEquals(model, reloaded.resolveRuntimeModel(new AiChatRuntimeResolveRequest()).getModel());
        }
    }

    private AiModelConfigServiceImpl service() {
        return new AiModelConfigServiceImpl(new ObjectMapper().findAndRegisterModules(), new AiModelConfigConverter(),
                () -> USER_ID, tempDirectory.resolve("ai-model-configs.json"), null);
    }
}
