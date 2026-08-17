package ai.chat2db.community.runtime.provider;

import java.time.Instant;

@FunctionalInterface
public interface ProviderLifecycleSink {

    void started(String runtimeExecutionId);

    default void processStarted(String runtimeExecutionId, long processId, Instant processStartInstant,
                                String executable) {
        started(runtimeExecutionId);
    }

    default void turnStarted(String runtimeExecutionId, String turnId) {
    }
}
