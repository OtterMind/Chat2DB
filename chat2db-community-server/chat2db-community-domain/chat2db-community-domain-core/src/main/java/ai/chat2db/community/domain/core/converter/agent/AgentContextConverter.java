package ai.chat2db.community.domain.core.converter.agent;

import ai.chat2db.community.domain.api.model.agent.context.AiAgentRunContext;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentRunContextRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;

public final class AgentContextConverter {
    private AgentContextConverter() { }

    public static AiAgentRunContext.Scope source2scope(WorkspaceDataSource source, String database, String schema) {
        return new AiAgentRunContext.Scope(String.valueOf(source.getId()), source.getAlias(), source.getType(), database, schema);
    }

    public static AiAgentRunContext.ObjectReference request2object(AiAgentRunContextRequest.ObjectReference object,
            AiAgentRunContext.Scope scope) {
        return new AiAgentRunContext.ObjectReference(scope.dataSourceId(), scope.dataSourceName(), scope.databaseType(),
                scope.database(), scope.schema(), object.type(), object.name(), object.source());
    }
}
