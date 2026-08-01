package ai.chat2db.community.tools.security.secretimport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Safe per-item import result. Never contains key material, ciphertext, or reversible error detail.
 */
public final class SecretImportItemResult {

    private String attemptId;
    private String itemId;
    private SecretImportItemStatus status;
    private SecretImportErrorCode errorCode;
    private String configId;
    private String name;
    private String provider;
    private String model;
    private Boolean hasApiKey;
    private Boolean defaultConfig;

    public static SecretImportItemResult failed(String attemptId, String itemId, SecretImportErrorCode errorCode) {
        SecretImportItemResult result = new SecretImportItemResult();
        result.attemptId = attemptId;
        result.itemId = itemId;
        result.status = SecretImportItemStatus.FAILED;
        result.errorCode = errorCode;
        return result;
    }

    public static SecretImportItemResult succeeded(String attemptId, String itemId, MaskedConfigAcknowledgement ack) {
        SecretImportItemResult result = new SecretImportItemResult();
        result.attemptId = attemptId;
        result.itemId = itemId;
        result.status = SecretImportItemStatus.SUCCEEDED;
        result.configId = ack.getConfigId();
        result.name = ack.getName();
        result.provider = ack.getProvider();
        result.model = ack.getModel();
        result.hasApiKey = ack.isHasApiKey();
        result.defaultConfig = ack.isDefaultConfig();
        return result;
    }

    public static SecretImportItemResult alreadyImported(String attemptId, String itemId,
                                                         MaskedConfigAcknowledgement ack) {
        SecretImportItemResult result = succeeded(attemptId, itemId, ack);
        result.status = SecretImportItemStatus.ALREADY_IMPORTED;
        return result;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public String getItemId() {
        return itemId;
    }

    public SecretImportItemStatus getStatus() {
        return status;
    }

    public SecretImportErrorCode getErrorCode() {
        return errorCode;
    }

    public String getConfigId() {
        return configId;
    }

    public String getName() {
        return name;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public Boolean getHasApiKey() {
        return hasApiKey;
    }

    public Boolean getDefaultConfig() {
        return defaultConfig;
    }

    /**
     * Intentionally always null — reversible error detail is forbidden on this boundary.
     */
    public String getErrorDetail() {
        return null;
    }

    public Map<String, Object> toSafeMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("attemptId", attemptId);
        map.put("itemId", itemId);
        map.put("status", status == null ? null : status.name());
        if (errorCode != null) {
            map.put("errorCode", errorCode.name());
        }
        if (configId != null) {
            map.put("configId", configId);
        }
        if (name != null) {
            map.put("name", name);
        }
        if (provider != null) {
            map.put("provider", provider);
        }
        if (model != null) {
            map.put("model", model);
        }
        if (hasApiKey != null) {
            map.put("hasApiKey", hasApiKey);
        }
        if (defaultConfig != null) {
            map.put("defaultConfig", defaultConfig);
        }
        return map;
    }
}
