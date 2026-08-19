package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.Environment;
import ai.chat2db.community.domain.api.model.datasource.DataSource;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSourceNamespace;
import ai.chat2db.community.domain.api.model.storage.WorkspaceNamespace;
import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.domain.api.service.sys.ISysEnvironmentService;
import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DataSourceEnvironmentEnricher {

    private final ISysEnvironmentService environmentService;

    public DataSourceEnvironmentEnricher(ISysEnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    public WorkspaceDataSource enrich(WorkspaceDataSource dataSource) {
        return enrichWorkspaceDataSource(dataSource, environmentById());
    }

    public List<WorkspaceDataSource> enrichWorkspaceDataSources(List<WorkspaceDataSource> dataSources) {
        Map<Long, Environment> environments = environmentById();
        return dataSources == null
                ? Collections.emptyList()
                : dataSources.stream()
                .map(dataSource -> enrichWorkspaceDataSource(dataSource, environments))
                .toList();
    }

    public WorkspaceDataSourceNamespace enrich(WorkspaceDataSourceNamespace namespace) {
        if (namespace == null) {
            return null;
        }
        Map<Long, Environment> environments = environmentById();
        WorkspaceDataSourceNamespace copy = new WorkspaceDataSourceNamespace();
        copy.setDataSources(enrichWorkspaceDataSources(namespace.getDataSources(), environments));
        copy.setNamespaces(namespace.getNamespaces() == null
                ? null
                : namespace.getNamespaces().stream()
                .map(item -> enrichWorkspaceNamespace(item, environments))
                .toList());
        return copy;
    }

    public List<Node> enrichTree(List<Node> nodes) {
        if (nodes == null) {
            return null;
        }
        Map<Long, Environment> environments = environmentById();
        return nodes.stream().map(node -> enrichNode(node, environments)).toList();
    }

    private WorkspaceNamespace enrichWorkspaceNamespace(WorkspaceNamespace namespace,
            Map<Long, Environment> environments) {
        if (namespace == null) {
            return null;
        }
        WorkspaceNamespace copy = new WorkspaceNamespace();
        BeanUtils.copyProperties(namespace, copy, "dataSources");
        copy.setDataSources(enrichWorkspaceDataSources(namespace.getDataSources(), environments));
        return copy;
    }

    private Node enrichNode(Node node, Map<Long, Environment> environments) {
        if (node == null) {
            return null;
        }
        Object data = node.getData();
        if (data instanceof DataSource dataSource) {
            data = enrichDataSource(dataSource, environments);
        } else if (data instanceof WorkspaceDataSource dataSource) {
            data = enrichWorkspaceDataSource(dataSource, environments);
        }
        return Node.builder()
                .type(node.getType())
                .id(node.getId())
                .data(data)
                .children(node.getChildren() == null
                        ? null
                        : node.getChildren().stream()
                        .map(child -> enrichNode(child, environments))
                        .toList())
                .build();
    }

    private DataSource enrichDataSource(DataSource source, Map<Long, Environment> environments) {
        if (source == null) {
            return null;
        }
        DataSource copy;
        try {
            copy = (DataSource) BeanUtils.instantiateClass(source.getClass());
        } catch (BeanInstantiationException exception) {
            copy = new DataSource();
        }
        BeanUtils.copyProperties(source, copy);
        if (copy.getEnvironmentId() != null) {
            copy.setEnvironment(environments.get(copy.getEnvironmentId()));
        }
        return copy;
    }

    private WorkspaceDataSource enrichWorkspaceDataSource(WorkspaceDataSource source,
            Map<Long, Environment> environments) {
        if (source == null) {
            return null;
        }
        WorkspaceDataSource copy;
        try {
            copy = (WorkspaceDataSource) BeanUtils.instantiateClass(source.getClass());
        } catch (BeanInstantiationException exception) {
            copy = new WorkspaceDataSource();
        }
        BeanUtils.copyProperties(source, copy);
        if (copy.getEnvironmentId() != null) {
            copy.setEnvironment(environments.get(copy.getEnvironmentId()));
        }
        return copy;
    }

    private List<WorkspaceDataSource> enrichWorkspaceDataSources(List<WorkspaceDataSource> dataSources,
            Map<Long, Environment> environments) {
        return dataSources == null
                ? null
                : dataSources.stream()
                .map(dataSource -> enrichWorkspaceDataSource(dataSource, environments))
                .toList();
    }

    private Map<Long, Environment> environmentById() {
        List<Environment> environments = environmentService.listAll();
        if (environments == null) {
            return Collections.emptyMap();
        }
        return environments.stream()
                .filter(Objects::nonNull)
                .filter(environment -> environment.getId() != null)
                .collect(Collectors.toMap(Environment::getId, Function.identity(), (left, right) -> right));
    }
}
