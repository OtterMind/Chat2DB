package ai.chat2db.community.tools.security.secretimport;

/**
 * Decrypted model-config fields held only inside the secret-import boundary.
 * Never serialize this type into logs, JCEF responses, or generic request stores.
 */
public final class ImportedApiKeyConfig {

    private String id;
    private String name;
    private String provider;
    private String model;
    private String apiKey;
    private String baseUrl;
    private String projectId;
    private String location;
    private Double temperature;
    private Integer maxTokens;
    private Boolean enabled;
    private Boolean defaultConfig;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(Boolean defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    /**
     * Drop the in-memory apiKey reference after persistence.
     * This is best-effort only: Java {@link String} instances cannot be securely wiped;
     * the import service zeros the decrypted payload {@code byte[]} separately.
     */
    public void destroySecret() {
        this.apiKey = null;
    }
}
