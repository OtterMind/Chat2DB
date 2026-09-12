package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.ai.AiModelConfig;
import ai.chat2db.community.domain.api.model.ai.AiModelConfigResponse;
import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiModelConfigSaveRequest;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.domain.core.converter.AiModelConfigConverter;
import ai.chat2db.community.tools.security.AesGcmUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelConfigServiceImplStorageTest {

    private static final long USER_ID = 42L;
    private static final String KEY = key(1);
    private static final String OTHER_KEY = key(2);
    private static final String API_KEY = "sk-test-1234567890";

    @TempDir
    Path tempDirectory;

    private ObjectMapper objectMapper;
    private Path storagePath;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        storagePath = tempDirectory.resolve("ai-model-configs.json");
    }

    @Test
    void persistsEncryptedApiKeyAndReloadsItForRuntimeUse() throws Exception {
        AiModelConfigServiceImpl service = service(KEY);

        AiModelConfigResponse saved = service.saveCurrentUserConfig(saveRequest("primary", API_KEY));

        String storedJson = Files.readString(storagePath);
        String storedApiKey = storedApiKeys().get(0);
        assertFalse(storedJson.contains(API_KEY));
        assertFalse(storedApiKey.isEmpty());
        assertEquals(Boolean.TRUE, saved.getHasApiKey());
        assertEquals("sk-t****7890", saved.getApiKeyMasked());
        assertEquals(API_KEY, resolveApiKey(service, saved.getId()));

        AiModelConfigServiceImpl reloaded = service(KEY);
        reloaded.init();

        assertEquals(API_KEY, resolveApiKey(reloaded, saved.getId()));
        assertEquals("sk-t****7890", reloaded.listCurrentUserConfigs().get(0).getApiKeyMasked());
    }

    @Test
    void springSelectsTheProductionConstructor() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AutowiredAnnotationBeanPostProcessor postProcessor = new AutowiredAnnotationBeanPostProcessor();
        postProcessor.setBeanFactory(beanFactory);
        beanFactory.addBeanPostProcessor(postProcessor);
        beanFactory.registerSingleton("objectMapper", objectMapper);
        beanFactory.registerSingleton("aiModelConfigConverter", new AiModelConfigConverter());
        beanFactory.registerSingleton("identityService", (IIdentityService) () -> USER_ID);

        String previousEncryptionKey = System.getProperty(AesGcmUtil.KEY_PROPERTY);
        System.setProperty(AesGcmUtil.KEY_PROPERTY, KEY);
        try {
            assertNotNull(beanFactory.createBean(AiModelConfigServiceImpl.class));
        } finally {
            if (previousEncryptionKey == null) {
                System.clearProperty(AesGcmUtil.KEY_PROPERTY);
            } else {
                System.setProperty(AesGcmUtil.KEY_PROPERTY, previousEncryptionKey);
            }
        }
    }

    @Test
    void usesRandomCiphertextForTheSameApiKey() throws Exception {
        AiModelConfigServiceImpl service = service(KEY);

        service.saveCurrentUserConfig(saveRequest("first", API_KEY));
        service.saveCurrentUserConfig(saveRequest("second", API_KEY));

        List<String> storedApiKeys = storedApiKeys();
        assertEquals(2, storedApiKeys.size());
        assertNotEquals(storedApiKeys.get(0), storedApiKeys.get(1));
        assertTrue(storedApiKeys.stream().noneMatch(String::isEmpty));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void newBlankApiKeyStaysAbsent(String apiKey) throws Exception {
        AiModelConfigServiceImpl service = service(KEY);

        AiModelConfigResponse saved = service.saveCurrentUserConfig(saveRequest("blank", apiKey));

        assertEquals(Boolean.FALSE, saved.getHasApiKey());
        assertEquals("", saved.getApiKeyMasked());
        JsonNode storedApiKey = objectMapper.readTree(storagePath.toFile()).path("configs").get(0).path("apiKey");
        assertTrue(storedApiKey.isNull() || "".equals(storedApiKey.asText()));
    }

    @Test
    void blankUpdatePreservesExistingApiKey() {
        AiModelConfigServiceImpl service = service(KEY);
        AiModelConfigResponse saved = service.saveCurrentUserConfig(saveRequest("primary", API_KEY));
        AiModelConfigSaveRequest update = saveRequest("updated", "  ");
        update.setId(saved.getId());

        AiModelConfigResponse updated = service.saveCurrentUserConfig(update);

        assertEquals(Boolean.TRUE, updated.getHasApiKey());
        assertEquals(API_KEY, resolveApiKey(service, saved.getId()));
    }

    @Test
    void loadFailsWithWrongKey() {
        AiModelConfigServiceImpl writer = service(KEY);
        writer.saveCurrentUserConfig(saveRequest("primary", API_KEY));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service(OTHER_KEY).init());

        assertLoadFailure(exception, "Unable to decrypt Community local storage value");
    }

    @Test
    void loadFailsForTamperedCiphertextWithoutReplacingExistingCache() throws Exception {
        AiModelConfigServiceImpl service = service(KEY);
        AiModelConfigResponse saved = service.saveCurrentUserConfig(saveRequest("primary", API_KEY));
        ObjectNode root = (ObjectNode) objectMapper.readTree(storagePath.toFile());
        ObjectNode config = (ObjectNode) root.path("configs").get(0);
        String storedApiKey = config.path("apiKey").asText();
        byte[] payload = Base64.getDecoder().decode(storedApiKey);
        payload[payload.length - 1] ^= 1;
        config.put("apiKey", Base64.getEncoder().encodeToString(payload));
        objectMapper.writeValue(storagePath.toFile(), root);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::init);

        assertLoadFailure(exception, "Unable to decrypt Community local storage value");
        assertEquals(API_KEY, resolveApiKey(service, saved.getId()));
    }

    @Test
    void loadRejectsPlaintextApiKey() throws Exception {
        AiModelConfig config = storedConfig("plain", "legacy-plaintext-key");
        writeStorage(config);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service(KEY).init());

        assertLoadFailure(exception, "Unable to decrypt Community local storage value");
    }

    @Test
    void loadPreservesNullAndEmptyApiKeys() throws Exception {
        writeStorage(storedConfig("null-key", null), storedConfig("empty-key", ""));
        AiModelConfigServiceImpl service = service(KEY);

        service.init();

        List<AiModelConfigResponse> configs = service.listCurrentUserConfigs();
        assertEquals(2, configs.size());
        assertTrue(configs.stream().allMatch(config -> Boolean.FALSE.equals(config.getHasApiKey())));
    }

    @Test
    void keepsNonCommunityStorageBehaviorUnchanged() throws Exception {
        AiModelConfigServiceImpl service = new AiModelConfigServiceImpl(objectMapper, new AiModelConfigConverter(),
                () -> USER_ID, storagePath, null);

        AiModelConfigResponse saved = service.saveCurrentUserConfig(saveRequest("non-community", API_KEY));

        assertTrue(Files.readString(storagePath).contains(API_KEY));
        AiModelConfigServiceImpl reloaded = new AiModelConfigServiceImpl(objectMapper, new AiModelConfigConverter(),
                () -> USER_ID, storagePath, null);
        reloaded.init();
        assertEquals(API_KEY, resolveApiKey(reloaded, saved.getId()));
    }

    @Test
    void persistToDiskDoesNotReuseLegacyFixedTempFile() throws Exception {
        Path legacyTemp = Path.of(storagePath + ".tmp");
        Files.writeString(legacyTemp, "keep", StandardCharsets.UTF_8);

        service(KEY).saveCurrentUserConfig(saveRequest("primary", API_KEY));

        assertEquals("keep", Files.readString(legacyTemp, StandardCharsets.UTF_8));
    }

    @Test
    void failedReplaceKeepsOriginalFileAndCleansRandomTemporaryFile() throws Exception {
        AiModelConfigServiceImpl initial = service(KEY);
        initial.saveCurrentUserConfig(saveRequest("primary", API_KEY));
        String original = Files.readString(storagePath, StandardCharsets.UTF_8);
        FailingAiModelConfigService failing = new FailingAiModelConfigService();

        assertThrows(IllegalStateException.class,
                () -> failing.saveCurrentUserConfig(saveRequest("replacement", "sk-replacement-123456")));

        assertEquals(original, Files.readString(storagePath, StandardCharsets.UTF_8));
        assertEquals(0, randomTempFileCount());
    }

    @Test
    void failedCreateDoesNotPublishConfigInMemory() {
        FailingAiModelConfigService failing = new FailingAiModelConfigService();

        assertThrows(IllegalStateException.class,
                () -> failing.saveCurrentUserConfig(saveRequest("replacement", "sk-replacement-123456")));

        assertTrue(failing.listCurrentUserConfigs().isEmpty());
    }

    @Test
    void failedUpdateRestoresConfigInMemory() {
        AiModelConfigResponse saved = service(KEY).saveCurrentUserConfig(saveRequest("primary", API_KEY));
        FailingAiModelConfigService failing = new FailingAiModelConfigService();
        failing.init();
        AiModelConfigSaveRequest update = saveRequest("replacement", "sk-replacement-123456");
        update.setId(saved.getId());

        assertThrows(IllegalStateException.class, () -> failing.saveCurrentUserConfig(update));

        List<AiModelConfigResponse> configs = failing.listCurrentUserConfigs();
        assertEquals(1, configs.size());
        assertEquals("primary", configs.get(0).getName());
        assertEquals(API_KEY, resolveApiKey(failing, saved.getId()));
    }

    @Test
    void failedDeleteRestoresConfigInMemory() {
        AiModelConfigResponse saved = service(KEY).saveCurrentUserConfig(saveRequest("primary", API_KEY));
        FailingAiModelConfigService failing = new FailingAiModelConfigService();
        failing.init();

        assertThrows(IllegalStateException.class, () -> failing.deleteCurrentUserConfig(saved.getId()));

        List<AiModelConfigResponse> configs = failing.listCurrentUserConfigs();
        assertEquals(1, configs.size());
        assertEquals(saved.getId(), configs.get(0).getId());
        assertEquals(API_KEY, resolveApiKey(failing, saved.getId()));
    }

    private AiModelConfigServiceImpl service(String key) {
        return new AiModelConfigServiceImpl(objectMapper, new AiModelConfigConverter(), () -> USER_ID,
                storagePath, new AesGcmUtil(key));
    }

    private AiModelConfigSaveRequest saveRequest(String name, String apiKey) {
        AiModelConfigSaveRequest request = new AiModelConfigSaveRequest();
        request.setName(name);
        request.setProvider("OPENAI");
        request.setModel("gpt-test");
        request.setApiKey(apiKey);
        return request;
    }

    private String resolveApiKey(AiModelConfigServiceImpl service, String configId) {
        AiChatRuntimeResolveRequest request = new AiChatRuntimeResolveRequest();
        request.setModelConfigId(configId);
        return service.resolveRuntimeModel(request).getApiKey();
    }

    private List<String> storedApiKeys() throws Exception {
        JsonNode configs = objectMapper.readTree(storagePath.toFile()).path("configs");
        List<String> apiKeys = new ArrayList<>();
        configs.forEach(config -> apiKeys.add(config.path("apiKey").asText()));
        return apiKeys;
    }

    private AiModelConfig storedConfig(String id, String apiKey) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setUserId(USER_ID);
        config.setName(id);
        config.setProvider("OPENAI");
        config.setModel("gpt-test");
        config.setApiKey(apiKey);
        return config;
    }

    private void writeStorage(AiModelConfig... configs) throws Exception {
        AiModelConfigServiceImpl.StorageData data = new AiModelConfigServiceImpl.StorageData();
        data.setConfigs(Arrays.asList(configs));
        objectMapper.writeValue(storagePath.toFile(), data);
    }

    private void assertLoadFailure(IllegalStateException exception, String causeMessage) {
        assertTrue(exception.getMessage().startsWith("Failed to load ai config from "));
        assertNotNull(exception.getCause());
        assertEquals(causeMessage, exception.getCause().getMessage());
    }

    private long randomTempFileCount() throws Exception {
        String prefix = storagePath.getFileName() + ".";
        try (var files = Files.list(tempDirectory)) {
            return files.filter(path -> {
                String fileName = path.getFileName().toString();
                return fileName.startsWith(prefix) && fileName.endsWith(".tmp");
            }).count();
        }
    }

    private class FailingAiModelConfigService extends AiModelConfigServiceImpl {

        FailingAiModelConfigService() {
            super(objectMapper, new AiModelConfigConverter(), () -> USER_ID, storagePath, new AesGcmUtil(KEY));
        }

        @Override
        protected void replaceStorageFile(Path temp, Path target) throws IOException {
            throw new IOException("forced replace failure");
        }
    }

    private static String key(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
