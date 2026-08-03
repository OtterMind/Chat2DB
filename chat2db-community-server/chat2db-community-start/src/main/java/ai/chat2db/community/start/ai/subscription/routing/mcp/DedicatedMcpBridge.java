package ai.chat2db.community.start.ai.subscription.routing.mcp;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Dedicated loopback MCP Streamable HTTP bridge for the ChatGPT subscription route.
 * Binds {@code 127.0.0.1} only, rotates a ≥256-bit capability per lifecycle, and
 * routes {@code tools/call} to the unique active attempt.
 */
public interface DedicatedMcpBridge {

    boolean isEnabled();

    Optional<String> disabledReason();

    /**
     * Starts the bridge for one supervisor lifecycle. Capability is rotated each start.
     */
    void start();

    void stop();

    Optional<InetSocketAddress> boundAddress();

    /**
     * High-entropy capability for the current lifecycle. Callers must not log the raw value.
     */
    Optional<String> capabilityToken();

    /**
     * Binds tool calls to exactly one active attempt (provider lease holder).
     */
    void bindActiveAttempt(String attemptId);

    void clearActiveAttempt();

    Optional<String> activeAttemptId();
}
