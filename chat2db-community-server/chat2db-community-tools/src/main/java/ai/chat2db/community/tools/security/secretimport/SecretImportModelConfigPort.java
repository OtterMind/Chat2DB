package ai.chat2db.community.tools.security.secretimport;

/**
 * Persistence port for writing decrypted API-key configs into the existing AES-GCM model-config store.
 * Implementations must encrypt at rest, never log the apiKey, and return only masked acknowledgement fields.
 */
public interface SecretImportModelConfigPort {

    MaskedConfigAcknowledgement writeAndReadback(ImportedApiKeyConfig config);
}
