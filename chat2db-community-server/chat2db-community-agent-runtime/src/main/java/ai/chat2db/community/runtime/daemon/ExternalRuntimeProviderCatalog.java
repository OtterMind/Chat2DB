package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.runtime.codex.CodexAppServerAdapter;
import ai.chat2db.community.runtime.dsh.DshRuntimeBridgeAdapter;
import ai.chat2db.community.runtime.hermes.HermesAcpAdapter;
import ai.chat2db.community.runtime.provider.ExternalProviderAdapter;

import java.util.List;
import java.util.Set;

/**
 * Exhaustive external Runtime classification. Adding a provider requires an
 * explicit entry here instead of silently falling back to another adapter.
 */
public final class ExternalRuntimeProviderCatalog {

    private static final Set<String> COMMON_CAPABILITIES = Set.of(
            "streaming", "sessionResume", "usage", "cancellation", "taskWorkspace");

    private ExternalRuntimeProviderCatalog() {
    }

    public static List<AgentRuntimeProviderEnum> providers() {
        return List.of(AgentRuntimeProviderEnum.CODEX, AgentRuntimeProviderEnum.HERMES,
                AgentRuntimeProviderEnum.DSH);
    }

    public static ExternalProviderAdapter createAdapter(AgentRuntimeProviderEnum provider) {
        return switch (requireExternal(provider)) {
            case CODEX -> new CodexAppServerAdapter();
            case HERMES -> new HermesAcpAdapter();
            case DSH -> new DshRuntimeBridgeAdapter();
            case SPRING_AI -> throw unsupported(provider);
        };
    }

    public static String protocolVersion(AgentRuntimeProviderEnum provider) {
        return switch (requireExternal(provider)) {
            case CODEX -> "codex-app-server-v2";
            case HERMES -> "acp-v1";
            case DSH -> "chat2db-dsh-bridge-v1";
            case SPRING_AI -> throw unsupported(provider);
        };
    }

    public static Set<String> capabilities(AgentRuntimeProviderEnum provider) {
        return switch (requireExternal(provider)) {
            case CODEX -> COMMON_CAPABILITIES;
            case HERMES, DSH -> Set.of("streaming", "sessionResume", "usage", "cancellation",
                    "taskWorkspace", "approvalBridge");
            case SPRING_AI -> throw unsupported(provider);
        };
    }

    public static List<String> versionArguments(AgentRuntimeProviderEnum provider) {
        return switch (requireExternal(provider)) {
            case CODEX, DSH -> List.of("--version");
            case HERMES -> List.of("acp", "--version");
            case SPRING_AI -> throw unsupported(provider);
        };
    }

    private static AgentRuntimeProviderEnum requireExternal(AgentRuntimeProviderEnum provider) {
        if (provider == null || !provider.isExternal()) {
            throw unsupported(provider);
        }
        return provider;
    }

    private static IllegalArgumentException unsupported(AgentRuntimeProviderEnum provider) {
        return new IllegalArgumentException("Unsupported external Runtime provider: " + provider);
    }
}
