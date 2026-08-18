package ai.chat2db.community.runtime.provider;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;

public interface ExternalProviderAdapter {

    AgentRuntimeProviderEnum provider();

    ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink eventSink,
                                    ProviderLifecycleSink lifecycleSink);

    void cancel(String runId);
}
