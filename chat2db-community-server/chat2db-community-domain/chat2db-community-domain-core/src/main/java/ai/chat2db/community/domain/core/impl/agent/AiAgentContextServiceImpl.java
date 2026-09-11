package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.context.AiAgentRunContext;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentRunContextRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.agent.IAiAgentContextService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.core.converter.agent.AgentContextConverter;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AiAgentContextServiceImpl implements IAiAgentContextService {
    private final IWorkspaceStorageFacade storage;
    private final Clock clock;

    @Autowired
    public AiAgentContextServiceImpl(IWorkspaceStorageFacade storage) { this(storage, Clock.systemUTC()); }

    AiAgentContextServiceImpl(IWorkspaceStorageFacade storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    @Override
    public AiAgentRunContext resolve(AiAgentRunContextRequest request) {
        String requestedZone = request == null ? null : request.timeZone();
        ZoneId zone = ZoneId.of("UTC");
        if (requestedZone != null && !requestedZone.isBlank()) {
            try {
                zone = ZoneId.of(requestedZone);
            } catch (DateTimeException error) {
                throw new IllegalArgumentException("Unknown timeZone: " + requestedZone, error);
            }
        }
        var environment = new AiAgentRunContext.Environment(zone.getId(),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(clock.instant().atZone(zone)),
                requestedZone == null || requestedZone.isBlank() ? "UTC_FALLBACK" : "CLIENT");
        Map<String, WorkspaceDataSource> sources = new HashMap<>();
        AiAgentRunContext.Scope selection = request == null || request.selection() == null ? null
                : scope(request.selection().dataSourceId(), request.selection().database(), request.selection().schema(), sources);
        List<AiAgentRunContext.ObjectReference> objects = new ArrayList<>();
        for (var object : request == null ? List.<AiAgentRunContextRequest.ObjectReference>of() : request.objects()) {
            var scope = scope(object.dataSourceId(), object.database(), object.schema(), sources);
            var reference = AgentContextConverter.request2object(object, scope);
            if (!objects.contains(reference)) objects.add(reference);
        }
        return new AiAgentRunContext(environment, selection, List.copyOf(objects));
    }

    private AiAgentRunContext.Scope scope(String id, String database, String schema, Map<String, WorkspaceDataSource> sources) {
        WorkspaceDataSource source = sources.computeIfAbsent(id, key -> {
            long numericId;
            try {
                numericId = Long.parseLong(key);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid context dataSourceId", error);
            }
            WorkspaceDataSource found = storage.queryDataSourceById(numericId, false);
            if (found == null) throw new IllegalArgumentException("Selected datasource is unavailable: " + key);
            return found;
        });
        return AgentContextConverter.source2scope(source, emptyToNull(database), emptyToNull(schema));
    }

    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
