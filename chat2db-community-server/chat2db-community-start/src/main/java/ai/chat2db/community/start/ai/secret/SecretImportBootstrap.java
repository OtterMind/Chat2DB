package ai.chat2db.community.start.ai.secret;

import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import ai.chat2db.community.tools.security.secretimport.EncryptedApiKeyImportService;
import ai.chat2db.community.tools.security.secretimport.SecretImportBoundary;
import ai.chat2db.community.tools.security.secretimport.SecretImportBoundaryRegistry;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.storage.ai.H2AiSubscriptionStateRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Registers the secret-specific JCEF boundary only after Spring is ready and
 * only for an explicitly enabled packaged Community desktop GUI.
 */
@Component
public final class SecretImportBootstrap {

    static final String FEATURE_PROPERTY = "chat2db.ai.secret-import.enabled";

    private final IAiModelConfigService modelConfigService;
    private EncryptedApiKeyImportService importService;

    public SecretImportBootstrap(IAiModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void registerAfterSpringReady() {
        boolean enabled = SecretImportRuntimeGate.isEnabled(
                Boolean.getBoolean(FEATURE_PROPERTY),
                ConfigUtils.isCommunity(),
                ConfigUtils.isDesktop(),
                ConfigUtils.isShowGUI(),
                ConfigUtils.isRelease());
        if (!enabled || importService != null) {
            return;
        }
        H2AiSubscriptionStateRepository repository = H2AiSubscriptionStateRepository.forCommunityProfile();
        repository.initialize();
        importService = new EncryptedApiKeyImportService(
                new AiModelConfigSecretImportPort(modelConfigService),
                new H2SecretImportLedgerPort(repository),
                5 * 60_000L);
        SecretImportBoundaryRegistry.register(new SecretImportBoundary(importService));
    }

    @PreDestroy
    public synchronized void shutdown() {
        SecretImportBoundaryRegistry.clear();
        if (importService != null) {
            importService.destroyAll();
            importService = null;
        }
    }
}
