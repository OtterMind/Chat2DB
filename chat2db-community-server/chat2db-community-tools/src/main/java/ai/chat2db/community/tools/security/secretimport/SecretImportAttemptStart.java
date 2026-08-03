package ai.chat2db.community.tools.security.secretimport;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SecretImportAttemptStart {

    private final String attemptId;
    private final String publicKeySpkiBase64;
    private final long expiresAtEpochMs;
    private final int schemaVersion;

    public SecretImportAttemptStart(String attemptId, String publicKeySpkiBase64, long expiresAtEpochMs,
                                    int schemaVersion) {
        this.attemptId = attemptId;
        this.publicKeySpkiBase64 = publicKeySpkiBase64;
        this.expiresAtEpochMs = expiresAtEpochMs;
        this.schemaVersion = schemaVersion;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public String getPublicKeySpkiBase64() {
        return publicKeySpkiBase64;
    }

    public long getExpiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    /**
     * Never expose private key material through the start contract.
     */
    public String getPrivateKeyMaterial() {
        return null;
    }

    public Map<String, Object> toSafeMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("attemptId", attemptId);
        map.put("publicKeySpkiBase64", publicKeySpkiBase64);
        map.put("expiresAtEpochMs", expiresAtEpochMs);
        map.put("schemaVersion", schemaVersion);
        return map;
    }
}
