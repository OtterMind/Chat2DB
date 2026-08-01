package ai.chat2db.community.tools.security.secretimport;

/**
 * Safe readback acknowledgement after encrypted model-config persistence.
 */
public final class MaskedConfigAcknowledgement {

    private String configId;
    private String name;
    private String provider;
    private String model;
    private boolean hasApiKey;
    private boolean defaultConfig;

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
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

    public boolean isHasApiKey() {
        return hasApiKey;
    }

    public void setHasApiKey(boolean hasApiKey) {
        this.hasApiKey = hasApiKey;
    }

    public boolean isDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(boolean defaultConfig) {
        this.defaultConfig = defaultConfig;
    }
}
