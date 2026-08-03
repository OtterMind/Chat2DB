package ai.chat2db.community.tools.security.secretimport;

/**
 * Encrypted import envelope. Ciphertext and wrapped key are opaque; never log this object.
 */
public final class EncryptedSecretImportEnvelope {

    private int schemaVersion;
    private String attemptId;
    private String itemId;
    private String nonceBase64;
    private long expiresAtEpochMs;
    private String wrappedKeyBase64;
    private String ciphertextBase64;
    private boolean confirmDefault;

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getNonceBase64() {
        return nonceBase64;
    }

    public void setNonceBase64(String nonceBase64) {
        this.nonceBase64 = nonceBase64;
    }

    public long getExpiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    public void setExpiresAtEpochMs(long expiresAtEpochMs) {
        this.expiresAtEpochMs = expiresAtEpochMs;
    }

    public String getWrappedKeyBase64() {
        return wrappedKeyBase64;
    }

    public void setWrappedKeyBase64(String wrappedKeyBase64) {
        this.wrappedKeyBase64 = wrappedKeyBase64;
    }

    public String getCiphertextBase64() {
        return ciphertextBase64;
    }

    public void setCiphertextBase64(String ciphertextBase64) {
        this.ciphertextBase64 = ciphertextBase64;
    }

    public boolean isConfirmDefault() {
        return confirmDefault;
    }

    public void setConfirmDefault(boolean confirmDefault) {
        this.confirmDefault = confirmDefault;
    }
}
