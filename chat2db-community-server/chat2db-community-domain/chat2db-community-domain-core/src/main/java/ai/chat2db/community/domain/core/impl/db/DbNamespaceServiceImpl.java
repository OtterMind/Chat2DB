package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePositionUpdateRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSourceNamespace;
import ai.chat2db.community.domain.api.model.workspace.Namespace;
import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.domain.api.service.db.IDbNamespaceService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class DbNamespaceServiceImpl implements IDbNamespaceService {

    private final IWorkspaceStorageFacade workspaceStorageFacade;
    private final DataSourceEnvironmentEnricher environmentEnricher;

    public DbNamespaceServiceImpl(IWorkspaceStorageFacade workspaceStorageFacade,
            DataSourceEnvironmentEnricher environmentEnricher) {
        this.workspaceStorageFacade = workspaceStorageFacade;
        this.environmentEnricher = environmentEnricher;
    }

    @Override
    public WorkspaceDataSourceNamespace getNamespaceDataSources() {
        return environmentEnricher.enrich(workspaceStorageFacade.getNamespaceDataSources());
    }

    @Override
    public WorkspaceDataSourceNamespace getNamespaceDataSources(boolean refresh) {
        try {
            return getNamespaceDataSources();
        } catch (Exception e) {
            if (!refresh) {
                log.error("namespace.datasource.list.fallback", e);
                return new WorkspaceDataSourceNamespace();
            }
            throw e;
        }
    }

    @Override
    public Long createNamespace(Namespace namespace) {
        return workspaceStorageFacade.createNamespace(namespace);
    }

    @Override
    public void updateNamespace(Namespace namespace) {
        workspaceStorageFacade.updateNamespace(namespace);
    }

    @Override
    public void deleteNamespace(Long id) {
        workspaceStorageFacade.deleteNamespace(id);
    }

    @Override
    public void updateDataSourcePosition(DbDataSourcePositionUpdateRequest request) {
        workspaceStorageFacade.updateDataSourcePosition(request);
    }

    @Override
    public List<Node> getTree() {
        return environmentEnricher.enrichTree(workspaceStorageFacade.getTree());
    }

    @Override
    public void updatePosition(Node dropToNode, Node dragNode, Integer dropPosition) {
        workspaceStorageFacade.updatePosition(dropToNode, dragNode, dropPosition);
    }

    @Override
    public void updatePositionIfChanged(Node dropToNode, Node dragNode, Integer dropPosition) {
        if (sameNode(dropToNode, dragNode)) {
            return;
        }
        updatePosition(dropToNode, dragNode, dropPosition);
    }

    private boolean sameNode(Node dropToNode, Node dragNode) {
        return dropToNode != null && dragNode != null
                && Objects.equals(dragNode.getId(), dropToNode.getId())
                && Objects.equals(dragNode.getType(), dropToNode.getType());
    }
}
