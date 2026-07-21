package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.AiModelCatalogItem;
import ai.chat2db.community.domain.api.model.ai.AiModelConfig;
import ai.chat2db.community.domain.api.model.ai.AiModelConfigResponse;
import ai.chat2db.community.domain.api.model.ai.AiModelOptionItem;
import ai.chat2db.community.domain.api.model.ai.AiRuntimeModel;
import ai.chat2db.community.domain.api.model.ai.ModelConfigTestResponse;
import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiModelConfigSaveRequest;
import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.domain.core.converter.AiModelConfigConverter;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.security.AesGcmUtil;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiModelConfigServiceImpl implements IAiModelConfigService {

    private static final String DEFAULT_GEMINI_LOCATION = "us-central1";
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final int TEST_ERROR_BODY_MAX_LENGTH = 2000;
    private static final String CONFIG_VALUE_PREFIX = "config:";
    private static final String PRESET_VALUE_PREFIX = "preset:";
    private static final String ERROR_MODEL_CONFIG_REQUIRED = "ai.model.config.required";

    private final ObjectMapper objectMapper;
    private final AiModelConfigConverter aiModelConfigConverter;
    private final IIdentityService identityService;
    private final AesGcmUtil aesGcmUtil;

    private final Path storagePath;

    private final Map<Long, List<AiModelConfig>> userConfigMap = new HashMap<>();
    private volatile Map<AiProviderEnum, List<String>> cachedPresetModelMap;
    private volatile long cachedPresetModelMapAt = 0L;
    private static final long PRESET_MODEL_CACHE_MILLIS = 5 * 60 * 1000L;

    @Autowired
    public AiModelConfigServiceImpl(ObjectMapper objectMapper, AiModelConfigConverter aiModelConfigConverter,
            IIdentityService identityService) {
        this(objectMapper, aiModelConfigConverter, identityService,
                Paths.get(ConfigUtils.getBasePath(), "ai-model-configs.json"),
                ConfigUtils.isCommunity() ? AesGcmUtil.configured() : null);
    }

    AiModelConfigServiceImpl(ObjectMapper objectMapper, AiModelConfigConverter aiModelConfigConverter,
            IIdentityService identityService, Path storagePath, AesGcmUtil aesGcmUtil) {
        this.objectMapper = objectMapper;
        this.aiModelConfigConverter = aiModelConfigConverter;
        this.identityService = identityService;
        this.storagePath = storagePath;
        this.aesGcmUtil = aesGcmUtil;
    }

    @PostConstruct
    public synchronized void init() {
        loadFromDisk();
    }

    public synchronized List<AiModelCatalogItem> listPresetModels() {
        return getPresetModelMap().entrySet().stream().map(entry -> {
            AiModelCatalogItem item = new AiModelCatalogItem();
            item.setProvider(entry.getKey().name());
            item.setModels(new ArrayList<>(entry.getValue()));
            return item;
        }).collect(Collectors.toList());
    }

    public synchronized List<AiModelOptionItem> listModelOptions() {
        Long userId = identityService.currentUserId();
        List<AiModelConfig> configs = userConfigMap.getOrDefault(userId, new ArrayList<>());
        List<AiModelOptionItem> options = new ArrayList<>();

        configs.stream()
                .filter(c -> Boolean.TRUE.equals(defaultBoolean(c.getEnabled(), Boolean.TRUE)))
                .sorted(Comparator.comparing((AiModelConfig c) -> !Boolean.TRUE.equals(c.getDefaultConfig()))
                        .thenComparing(AiModelConfig::getGmtModified, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(config -> {
                    if (Objects.isNull(config.getProvider()) || StringUtils.isBlank(config.getModel())) {
                        return;
                    }
                    AiModelOptionItem item = new AiModelOptionItem();
                    item.setValue(CONFIG_VALUE_PREFIX + config.getId());
                    item.setLabel(StringUtils.defaultIfBlank(config.getName(), config.getModel()));
                    item.setProvider(config.getProvider());
                    item.setModel(config.getModel());
                    item.setModelConfigId(config.getId());
                    item.setCustomOption(Boolean.TRUE);
                    item.setDefaultOption(Boolean.TRUE.equals(config.getDefaultConfig()));
                    options.add(item);
                });

        getPresetModelMap().forEach((provider, models) -> {
            for (String model : models) {
                AiModelOptionItem item = new AiModelOptionItem();
                item.setValue(presetOptionValue(provider, model));
                item.setLabel(model);
                item.setProvider(provider.name());
                item.setModel(model);
                item.setCustomOption(Boolean.FALSE);
                item.setDefaultOption(Boolean.FALSE);
                options.add(item);
            }
        });

        if (options.stream().noneMatch(i -> Boolean.TRUE.equals(i.getDefaultOption())) && CollectionUtils.isNotEmpty(options)) {
            options.get(0).setDefaultOption(Boolean.TRUE);
        }
        return options;
    }

    public synchronized List<AiModelConfigResponse> listCurrentUserConfigs() {
        Long userId = identityService.currentUserId();
        List<AiModelConfig> configs = userConfigMap.getOrDefault(userId, new ArrayList<>());
        return configs.stream()
                .sorted(Comparator.comparing((AiModelConfig c) -> !Boolean.TRUE.equals(c.getDefaultConfig()))
                        .thenComparing(AiModelConfig::getGmtModified, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(aiModelConfigConverter::toMaskedResponse)
                .collect(Collectors.toList());
    }

    public synchronized AiModelConfigResponse saveCurrentUserConfig(AiModelConfigSaveRequest request) {
        Long userId = identityService.currentUserId();
        List<AiModelConfig> configs = userConfigMap.computeIfAbsent(userId, key -> new ArrayList<>());

        AiModelConfig config = findById(configs, request.getId());
        boolean isNew = Objects.isNull(config);
        if (isNew) {
            config = new AiModelConfig();
            config.setId(StringUtils.defaultIfBlank(request.getId(), UUID.randomUUID().toString()));
            config.setUserId(userId);
            config.setGmtCreate(LocalDateTime.now());
            configs.add(config);
        }

        String originalApiKey = config.getApiKey();
        updateModelConfig(request, config);
        config.setEnabled(defaultBoolean(config.getEnabled(), Boolean.TRUE));
        config.setDefaultConfig(defaultBoolean(config.getDefaultConfig(), Boolean.FALSE));
        config.setGmtModified(LocalDateTime.now());

        if (StringUtils.isBlank(request.getApiKey()) && StringUtils.isNotBlank(originalApiKey)) {
            config.setApiKey(originalApiKey);
        } else if (StringUtils.isNotBlank(request.getApiKey())) {
            config.setApiKey(request.getApiKey().trim());
        }

        if (Boolean.TRUE.equals(config.getDefaultConfig())) {
            String currentId = config.getId();
            configs.forEach(item -> {
                if (!Objects.equals(currentId, item.getId())) {
                    item.setDefaultConfig(Boolean.FALSE);
                }
            });
        } else if (CollectionUtils.isNotEmpty(configs) && configs.stream().noneMatch(c -> Boolean.TRUE.equals(c.getDefaultConfig()))) {
            configs.get(0).setDefaultConfig(Boolean.TRUE);
        }

        persistToDisk();
        return aiModelConfigConverter.toMaskedResponse(config);
    }

    public synchronized void deleteCurrentUserConfig(String id) {
        Long userId = identityService.currentUserId();
        List<AiModelConfig> configs = userConfigMap.getOrDefault(userId, new ArrayList<>());
        if (CollectionUtils.isEmpty(configs)) {
            return;
        }
        boolean removedDefault = configs.stream().anyMatch(c -> Objects.equals(c.getId(), id) && Boolean.TRUE.equals(c.getDefaultConfig()));
        configs.removeIf(c -> Objects.equals(c.getId(), id));
        if (removedDefault && CollectionUtils.isNotEmpty(configs)) {
            configs.get(0).setDefaultConfig(Boolean.TRUE);
        }
        persistToDisk();
    }

    public ModelConfigTestResponse testModelConfig(AiModelConfigSaveRequest request) {
        if (AiProviderEnum.from(request.getProvider()) == AiProviderEnum.OPENAI) {
            return testOpenAiCompatibleConfig(request);
        }
        return ModelConfigTestResponse.failure(null, null,
                "Connection test currently supports OpenAI-compatible models only.");
    }

    public synchronized AiRuntimeModel resolveRuntimeModel(AiChatRuntimeResolveRequest request) {
        Long userId = identityService.currentUserId();
        AiModelConfig baseConfig = null;
        boolean systemPreset = false;
        if (StringUtils.isNotBlank(request.getModelConfigId())) {
            String modelConfigId = request.getModelConfigId().trim();
            if (modelConfigId.startsWith(PRESET_VALUE_PREFIX)) {
                throw new BusinessException(ERROR_MODEL_CONFIG_REQUIRED);
            } else {
                String resolvedId = modelConfigId.startsWith(CONFIG_VALUE_PREFIX)
                        ? modelConfigId.substring(CONFIG_VALUE_PREFIX.length())
                        : modelConfigId;
                baseConfig = findById(userConfigMap.getOrDefault(userId, new ArrayList<>()), resolvedId);
                if (Objects.isNull(baseConfig)) {
                    throw new BusinessException("ai.model.config.notFound");
                }
            }
        } else if (StringUtils.isNotBlank(request.getProvider()) && StringUtils.isNotBlank(request.getModel())) {
            baseConfig = new AiModelConfig();
            baseConfig.setProvider(request.getProvider());
            baseConfig.setModel(request.getModel());
            baseConfig.setApiKey(request.getApiKey());
            baseConfig.setBaseUrl(request.getBaseUrl());
            baseConfig.setProjectId(request.getProjectId());
            baseConfig.setLocation(request.getLocation());
            baseConfig.setTemperature(request.getTemperature());
            baseConfig.setMaxTokens(request.getMaxTokens());
            systemPreset = isPresetModel(request.getProvider(), request.getModel())
                    && StringUtils.isBlank(request.getApiKey())
                    && StringUtils.isBlank(request.getBaseUrl())
                    && StringUtils.isBlank(request.getProjectId())
                    && StringUtils.isBlank(request.getLocation());
            if (systemPreset) {
                throw new BusinessException(ERROR_MODEL_CONFIG_REQUIRED);
            }
        } else {
            baseConfig = userConfigMap.getOrDefault(userId, new ArrayList<>()).stream()
                    .filter(c -> Boolean.TRUE.equals(c.getDefaultConfig()))
                    .findFirst()
                    .orElse(null);
            if (Objects.isNull(baseConfig)) {
                throw new BusinessException(ERROR_MODEL_CONFIG_REQUIRED);
            }
        }

        AiRuntimeModel runtimeModel = new AiRuntimeModel();
        runtimeModel.setSystemPreset(systemPreset);
        runtimeModel.setProvider(defaultValue(request.getProvider(), baseConfig.getProvider()));
        runtimeModel.setModel(defaultValue(trimToNull(request.getModel()), trimToNull(baseConfig.getModel())));
        runtimeModel.setApiKey(defaultValue(trimToNull(request.getApiKey()), trimToNull(baseConfig.getApiKey())));
        runtimeModel.setBaseUrl(defaultValue(trimToNull(request.getBaseUrl()), trimToNull(baseConfig.getBaseUrl())));
        runtimeModel.setProjectId(defaultValue(trimToNull(request.getProjectId()), trimToNull(baseConfig.getProjectId())));
        runtimeModel.setLocation(defaultValue(trimToNull(request.getLocation()), trimToNull(baseConfig.getLocation())));
        runtimeModel.setTemperature(defaultValue(request.getTemperature(), baseConfig.getTemperature()));
        runtimeModel.setMaxTokens(defaultValue(request.getMaxTokens(), baseConfig.getMaxTokens()));

        fillRuntimeFallback(runtimeModel);
        normalizeRuntimeBaseUrl(runtimeModel);
        validateRuntimeModel(runtimeModel);
        return runtimeModel;
    }

    /**
     * Spring AI's OpenAiApi and AnthropicApi append version-prefixed request paths
     * ("/v1/chat/completions", "/v1/messages") to the configured base URL, so a base
     * URL that already ends with "/v1" would request ".../v1/v1/..." and fail with 404.
     * Strip the trailing "/v1" so base URLs with and without it hit the same endpoint.
     */
    private void normalizeRuntimeBaseUrl(AiRuntimeModel runtimeModel) {
        AiProviderEnum provider = AiProviderEnum.from(runtimeModel.getProvider());
        if (provider != AiProviderEnum.OPENAI && provider != AiProviderEnum.CLAUDE) {
            return;
        }
        runtimeModel.setBaseUrl(stripTrailingV1(runtimeModel.getBaseUrl()));
    }

    private String stripTrailingV1(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return baseUrl;
        }
        String normalized = StringUtils.removeEnd(baseUrl.trim(), "/");
        normalized = StringUtils.removeEnd(normalized, "/v1");
        return StringUtils.removeEnd(normalized, "/");
    }

    private void fillRuntimeFallback(AiRuntimeModel runtimeModel) {
        if (Objects.isNull(runtimeModel.getProvider())) {
            return;
        }

        AiProviderEnum provider = AiProviderEnum.from(runtimeModel.getProvider());
        AiModelConfig providerConfig = findPreferredConfigByProvider(identityService.currentUserId(),
                runtimeModel.getProvider());
        if (Objects.nonNull(providerConfig)) {
            runtimeModel.setApiKey(defaultValue(trimToNull(runtimeModel.getApiKey()), trimToNull(providerConfig.getApiKey())));
            runtimeModel.setBaseUrl(defaultValue(trimToNull(runtimeModel.getBaseUrl()), trimToNull(providerConfig.getBaseUrl())));
            runtimeModel.setProjectId(defaultValue(trimToNull(runtimeModel.getProjectId()), trimToNull(providerConfig.getProjectId())));
            runtimeModel.setLocation(defaultValue(trimToNull(runtimeModel.getLocation()), trimToNull(providerConfig.getLocation())));
            runtimeModel.setTemperature(defaultValue(runtimeModel.getTemperature(), providerConfig.getTemperature()));
            runtimeModel.setMaxTokens(defaultValue(runtimeModel.getMaxTokens(), providerConfig.getMaxTokens()));
        }

        if (provider == AiProviderEnum.OPENAI) {
            runtimeModel.setApiKey(defaultValue(trimToNull(runtimeModel.getApiKey()), trimToNull(System.getenv("OPENAI_API_KEY"))));
            runtimeModel.setBaseUrl(defaultValue(trimToNull(runtimeModel.getBaseUrl()), trimToNull(System.getenv("OPENAI_BASE_URL"))));
            return;
        }
        if (provider == AiProviderEnum.CLAUDE) {
            runtimeModel.setApiKey(defaultValue(trimToNull(runtimeModel.getApiKey()), trimToNull(System.getenv("ANTHROPIC_API_KEY"))));
            runtimeModel.setBaseUrl(defaultValue(trimToNull(runtimeModel.getBaseUrl()), trimToNull(System.getenv("ANTHROPIC_BASE_URL"))));
            return;
        }
        if (provider == AiProviderEnum.GEMINI) {
            runtimeModel.setProjectId(defaultValue(trimToNull(runtimeModel.getProjectId()), trimToNull(System.getenv("GOOGLE_CLOUD_PROJECT"))));
            runtimeModel.setLocation(defaultValue(trimToNull(runtimeModel.getLocation()), trimToNull(System.getenv("GOOGLE_CLOUD_LOCATION"))));
            runtimeModel.setLocation(defaultValue(runtimeModel.getLocation(), DEFAULT_GEMINI_LOCATION));
        }
    }

    private void validateRuntimeModel(AiRuntimeModel runtimeModel) {
        if (Objects.isNull(runtimeModel.getProvider())) {
            throw new IllegalArgumentException("provider is required");
        }
        if (StringUtils.isBlank(runtimeModel.getModel())) {
            throw new IllegalArgumentException("model is required");
        }
        AiProviderEnum provider = AiProviderEnum.from(runtimeModel.getProvider());
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported provider: " + runtimeModel.getProvider());
        }
        if (provider == AiProviderEnum.OPENAI || provider == AiProviderEnum.CLAUDE) {
            return;
        }
        if (provider == AiProviderEnum.GEMINI) {
        }
    }


    private synchronized void loadFromDisk() {
        if (!Files.exists(storagePath)) {
            return;
        }
        try {
            StorageData data = objectMapper.readValue(storagePath.toFile(), StorageData.class);
            Map<Long, List<AiModelConfig>> loadedConfigMap = new HashMap<>();
            if (CollectionUtils.isNotEmpty(data.getConfigs())) {
                data.getConfigs().forEach(config -> {
                    config.setApiKey(decryptApiKey(config.getApiKey()));
                    Long userId = defaultValue(config.getUserId(), 0L);
                    loadedConfigMap.computeIfAbsent(userId, key -> new ArrayList<>()).add(config);
                });
            }
            userConfigMap.clear();
            userConfigMap.putAll(loadedConfigMap);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load ai config from " + storagePath, e);
        }
    }

    private synchronized void persistToDisk() {
        try {
            Files.createDirectories(storagePath.getParent());
            StorageData data = new StorageData();
            List<AiModelConfig> all = userConfigMap.values().stream()
                    .flatMap(List::stream)
                    .map(this::encryptedCopy)
                    .collect(Collectors.toList());
            data.setConfigs(all);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), data);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist ai config to " + storagePath, e);
        }
    }

    private AiModelConfig encryptedCopy(AiModelConfig config) {
        AiModelConfig copy = new AiModelConfig();
        BeanUtils.copyProperties(config, copy);
        copy.setApiKey(encryptApiKey(config.getApiKey()));
        return copy;
    }

    private String encryptApiKey(String apiKey) {
        if (aesGcmUtil == null || apiKey == null || apiKey.isEmpty()) {
            return apiKey;
        }
        return aesGcmUtil.encryptAiModelApiKey(apiKey);
    }

    private String decryptApiKey(String storedApiKey) {
        if (aesGcmUtil == null || storedApiKey == null || storedApiKey.isEmpty()) {
            return storedApiKey;
        }
        return aesGcmUtil.decryptAiModelApiKey(storedApiKey);
    }

    private AiModelConfig findById(List<AiModelConfig> configs, String id) {
        if (StringUtils.isBlank(id) || CollectionUtils.isEmpty(configs)) {
            return null;
        }
        return configs.stream().filter(c -> Objects.equals(c.getId(), id)).findFirst().orElse(null);
    }

    private boolean isPresetModel(String providerValue, String model) {
        AiProviderEnum provider = AiProviderEnum.from(providerValue);
        if (provider == null || StringUtils.isBlank(model)) {
            return false;
        }
        return getPresetModelMap().getOrDefault(provider, new ArrayList<>()).contains(model);
    }

    private String presetOptionValue(AiProviderEnum provider, String model) {
        return PRESET_VALUE_PREFIX + provider.name() + ":" + model;
    }

    private Map<AiProviderEnum, List<String>> getPresetModelMap() {
        long now = System.currentTimeMillis();
        if (cachedPresetModelMap != null && now - cachedPresetModelMapAt < PRESET_MODEL_CACHE_MILLIS) {
          return cachedPresetModelMap;
        }

        synchronized (this) {
            if (cachedPresetModelMap != null && now - cachedPresetModelMapAt < PRESET_MODEL_CACHE_MILLIS) {
                return cachedPresetModelMap;
            }
            Map<AiProviderEnum, List<String>> fallback = localPresetModelMap();
            cachedPresetModelMap = fallback;
            cachedPresetModelMapAt = now;
            return fallback;
        }
    }

    private Map<AiProviderEnum, List<String>> localPresetModelMap() {
        Map<AiProviderEnum, List<String>> presets = new EnumMap<>(AiProviderEnum.class);
        presets.put(AiProviderEnum.OPENAI, List.of("gpt-5.2"));
        presets.put(AiProviderEnum.CLAUDE, List.of("claude-sonnet-4-5-20250929", "claude-haiku-4-5-20251001"));
        presets.put(AiProviderEnum.GEMINI, List.of("gemini-2.5-pro", "gemini-2.5-flash"));
        return presets;
    }

    private ModelConfigTestResponse testOpenAiCompatibleConfig(AiModelConfigSaveRequest request) {
        String baseUrl = StringUtils.defaultIfBlank(trimToNull(request.getBaseUrl()), DEFAULT_OPENAI_BASE_URL);
        String endpoint = appendPath(stripTrailingV1(baseUrl), "/v1/chat/completions");
        String apiKey = resolveTestApiKey(request);
        if (StringUtils.isBlank(apiKey)) {
            return ModelConfigTestResponse.failure(endpoint, null, "API Key is required for the connection test.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.getModel());
        payload.put("messages", List.of(Map.of("role", "user", "content", "ping")));
        payload.put("max_tokens", 1);
        if (Objects.nonNull(request.getTemperature())) {
            payload.put("temperature", request.getTemperature());
        }

        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(10_000);
            requestFactory.setReadTimeout(20_000);
            ResponseEntity<String> response = RestClient.builder()
                    .requestFactory(requestFactory)
                    .build()
                    .post()
                    .uri(URI.create(endpoint))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toEntity(String.class);
            HttpStatusCode statusCode = response.getStatusCode();
            if (statusCode.is2xxSuccessful()) {
                return ModelConfigTestResponse.success(endpoint);
            }
            return ModelConfigTestResponse.failure(endpoint, statusCode.value(),
                    truncate(StringUtils.defaultIfBlank(response.getBody(), statusCode.toString())));
        } catch (RestClientResponseException e) { // impl-contract: fallback - connection test failures are returned as test result.
            return ModelConfigTestResponse.failure(endpoint, e.getStatusCode().value(),
                    truncate(StringUtils.defaultIfBlank(e.getResponseBodyAsString(), e.getMessage())));
        } catch (Exception e) { // impl-contract: fallback - connection test failures are returned as test result.
            return ModelConfigTestResponse.failure(endpoint, null, truncate(e.getMessage()));
        }
    }

    private String resolveTestApiKey(AiModelConfigSaveRequest request) {
        if (StringUtils.isNotBlank(request.getApiKey())) {
            return request.getApiKey().trim();
        }
        if (StringUtils.isBlank(request.getId())) {
            return null;
        }
        Long userId = identityService.currentUserId();
        AiModelConfig config = findById(userConfigMap.getOrDefault(userId, new ArrayList<>()), request.getId());
        return config == null ? null : trimToNull(config.getApiKey());
    }

    private String appendPath(String baseUrl, String path) {
        String normalizedBaseUrl = StringUtils.removeEnd(baseUrl.trim(), "/");
        return normalizedBaseUrl + path;
    }

    private String truncate(String value) {
        if (StringUtils.isBlank(value) || value.length() <= TEST_ERROR_BODY_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, TEST_ERROR_BODY_MAX_LENGTH) + "...";
    }

    private AiModelConfig findPreferredConfigByProvider(Long userId, String providerValue) {
        AiProviderEnum provider = AiProviderEnum.from(providerValue);
        if (provider == null) {
            return null;
        }
        List<AiModelConfig> configs = userConfigMap.getOrDefault(userId, new ArrayList<>());
        return configs.stream()
                .filter(c -> provider == AiProviderEnum.from(c.getProvider()))
                .filter(c -> Boolean.TRUE.equals(defaultBoolean(c.getEnabled(), Boolean.TRUE)))
                .sorted(Comparator.comparing((AiModelConfig c) -> !Boolean.TRUE.equals(c.getDefaultConfig()))
                        .thenComparing(AiModelConfig::getGmtModified, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
    }

    private void updateModelConfig(AiModelConfigSaveRequest request, AiModelConfig config) {
        config.setName(request.getName());
        config.setProvider(request.getProvider());
        config.setModel(request.getModel());
        config.setBaseUrl(request.getBaseUrl());
        config.setProjectId(request.getProjectId());
        config.setLocation(request.getLocation());
        config.setTemperature(request.getTemperature());
        config.setMaxTokens(request.getMaxTokens());
        config.setEnabled(request.getEnabled());
        config.setDefaultConfig(request.getDefaultConfig());
    }

    private static <T> T defaultValue(T value, T fallback) {
        return Objects.nonNull(value) ? value : fallback;
    }

    private static Boolean defaultBoolean(Boolean value, Boolean fallback) {
        return Objects.nonNull(value) ? value : fallback;
    }

    private static String trimToNull(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    @Data
    public static class StorageData {
        private List<AiModelConfig> configs = new ArrayList<>();
    }
}
