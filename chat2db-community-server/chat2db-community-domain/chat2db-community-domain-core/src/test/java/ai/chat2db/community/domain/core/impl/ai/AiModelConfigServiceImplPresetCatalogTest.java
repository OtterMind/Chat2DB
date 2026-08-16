package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.AiModelCatalogItem;
import ai.chat2db.community.domain.api.model.ai.AiModelOptionItem;
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

        assertEquals(List.of("MiniMax-M3", "MiniMax-M2.7"), minimax.getModels());
    }

    @Test
    void modelOptionsIncludeMinimaxPresets() {
        AiModelConfigServiceImpl service = service();

        List<AiModelOptionItem> options = service.listModelOptions();

        assertTrue(options.stream().anyMatch(option ->
                AiProviderEnum.MINIMAX.name().equals(option.getProvider())
                        && "MiniMax-M3".equals(option.getModel())));
        assertTrue(options.stream().anyMatch(option ->
                AiProviderEnum.MINIMAX.name().equals(option.getProvider())
                        && "MiniMax-M2.7".equals(option.getModel())));
    }

    private AiModelConfigServiceImpl service() {
        return new AiModelConfigServiceImpl(new ObjectMapper().findAndRegisterModules(), new AiModelConfigConverter(),
                () -> USER_ID, tempDirectory.resolve("ai-model-configs.json"), null);
    }
}
