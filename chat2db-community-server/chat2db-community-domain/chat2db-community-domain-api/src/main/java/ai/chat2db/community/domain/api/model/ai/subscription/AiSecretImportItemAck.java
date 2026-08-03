package ai.chat2db.community.domain.api.model.ai.subscription;

/** Secret-free durable acknowledgement for one legacy model-config import item. */
public record AiSecretImportItemAck(
        String itemId,
        String configId,
        String name,
        String provider,
        String model,
        boolean hasCredential,
        boolean defaultConfig) {
}

