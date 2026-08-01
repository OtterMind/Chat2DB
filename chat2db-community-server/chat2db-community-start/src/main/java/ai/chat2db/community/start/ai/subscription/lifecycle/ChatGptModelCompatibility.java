package ai.chat2db.community.start.ai.subscription.lifecycle;

import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerModelDescriptor;

/**
 * Versioned Chat2DB compatibility policy applied after authenticated dynamic discovery.
 * <p>
 * Subscription MCP is forced onto the direct function/MCP HTTP path. Models that only support
 * CodeMode nested MCP ({@code tool_mode=code_mode_only}) are excluded so they never appear in the
 * selector or authorize a turn that would hang after tools/list.
 */
final class ChatGptModelCompatibility {

    static final int POLICY_VERSION = 2;

    private ChatGptModelCompatibility() {
    }

    static boolean isCompatible(AppServerModelDescriptor descriptor) {
        return descriptor != null
                && !descriptor.hidden()
                && descriptor.id() != null
                && !descriptor.id().isBlank()
                && !descriptor.codeModeOnly()
                && descriptor.inputModalities().stream().anyMatch("text"::equalsIgnoreCase);
    }
}
