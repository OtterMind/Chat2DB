package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.enums.NodeTypeEnum;
import ai.chat2db.community.domain.api.model.datasource.DataSource;
import ai.chat2db.community.domain.api.model.datasource.DataSourceIdentityColorUtils;
import ai.chat2db.community.domain.api.model.datasource.DataSourceNamespace;
import ai.chat2db.community.domain.api.model.workspace.Namespace;
import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeanUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DataSourceStorage extends SmallDataStorage<DataSource> {

    public static final DataSourceStorage INSTANCE = new DataSourceStorage();

    protected DataSourceStorage() {
        super("datasource", DataSource.class);
    }

    protected DataSourceStorage(File storageFile) {
        super(storageFile, DataSource.class);
    }

    @Override
    public List<DataSource> getDataList() {
        List<DataSource> dataSourceList = super.getDataList();
        if (CollectionUtils.isEmpty(dataSourceList)) {
            return Lists.newArrayList();
        }
        return dataSourceList;
    }


    public DataResult<DataSourceNamespace> getNamespaceDatasource() {
        List<Namespace> namespaces = NamespaceStorage.INSTANCE.getDataList();
        List<DataSource> dataSources = getDataList();
        Map<Long, DataSource> dataSourceMap = dataSources.stream().collect(Collectors.toMap(DataSource::getId, Function.identity()));
        DataSourceNamespace dataSourceNamespace = new DataSourceNamespace();
        List<Namespace> namespaceList = new ArrayList<>();
        for (Namespace namespace : namespaces) {
            List<Long> datasourceIds = namespace.getDatasourceIds();
            List<DataSource> dataSourceList1 = new ArrayList<>();
            if (!CollectionUtils.isEmpty(datasourceIds)) {
                for (Long datasourceId : datasourceIds) {
                    DataSource dataSource = dataSourceMap.get(datasourceId);
                    if (dataSource != null) {
                        dataSourceMap.remove(datasourceId);
                        dataSourceList1.add(dataSource);
                    }
                }
            }
            namespace.setDataSources(dataSourceList1);
            namespaceList.add(namespace);
        }
        List<DataSource> dataSourceList = new ArrayList<>(dataSourceMap.values());
        dataSourceNamespace.setNamespaces(namespaceList);
        dataSourceNamespace.setDataSources(dataSourceList);
        return DataResult.of(dataSourceNamespace);
    }

    public List<Node> getNodes() {
        List<Node> nodes = TreeNodeStorage.INSTANCE.getNodes();
        if (CollectionUtils.isEmpty(nodes)) {
            nodes = buildTree();
            TreeNodeStorage.INSTANCE.createTree(nodes);
            return nodes;
        }
        List<DataSource> dataSourceList = getDataList();
        List<Namespace> namespaceList = NamespaceStorage.INSTANCE.getDataList();
        Map<Long, DataSource> dataSourceMap = new HashMap<>();
        Map<Long, Namespace> namespaceMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(dataSourceList)) {
            for (DataSource dataSource : dataSourceList) {
                dataSourceMap.put(dataSource.getId(), dataSource);
            }
        }
        if (CollectionUtils.isNotEmpty(namespaceList)) {
            for (Namespace namespace : namespaceList) {
                namespaceMap.put(namespace.getId(), namespace);
            }
        }
        buildTreeResult(nodes, dataSourceMap, namespaceMap);
        return nodes;
    }

    private void buildTreeResult(List<Node> children, Map<Long, DataSource> dataSourceMap, Map<Long, Namespace> namespaceMap) {
        if (CollectionUtils.isEmpty(children)) {
            return;
        }
        for (Node node : children) {
            if (NodeTypeEnum.NAMESPACE.name().equals(node.getType())) {
                Namespace namespace = namespaceMap.get(node.getId());
                if (namespace != null) {
                    node.setData(namespace);
                }
                List<Node> children1 = node.getChildren();
                buildTreeResult(children1, dataSourceMap, namespaceMap);
            } else if (NodeTypeEnum.DATA_SOURCE.name().equals(node.getType())) {
                DataSource dataSource = dataSourceMap.get(node.getId());
                if (dataSource != null) {
                    node.setData(dataSource);
                }
            }
            if (CollectionUtils.isNotEmpty(node.getChildren())) {
                buildTreeResult(node.getChildren(), dataSourceMap, namespaceMap);
            }
        }
    }

    private List<Node> buildTree() {
        DataResult<DataSourceNamespace> result = getNamespaceDatasource();
        if (result.getData() == null) {
            return Lists.newArrayList();
        }
        List<Node> nodes = Lists.newArrayList();
        DataSourceNamespace dataSourceNamespace = result.getData();
        List<Namespace> namespaceList = dataSourceNamespace.getNamespaces();
        List<DataSource> dataSourceList = dataSourceNamespace.getDataSources();
        if (CollectionUtils.isNotEmpty(namespaceList)) {
            for (Namespace namespace : namespaceList) {
                Node node = new Node();
                node.setId(namespace.getId());
                node.setType(NodeTypeEnum.NAMESPACE.name());
                node.setData(namespace);
                List<DataSource> dataSources = namespace.getDataSources();
                if (CollectionUtils.isNotEmpty(dataSources)) {
                    namespace.setDataSources(null);
                    List<Node> children = Lists.newArrayList();
                    for (DataSource dataSource : dataSources) {
                        Node node1 = new Node();
                        node1.setId(dataSource.getId());
                        node1.setType(NodeTypeEnum.DATA_SOURCE.name());
                        node1.setData(dataSource);
                        children.add(node1);
                    }
                    node.setChildren(children);
                }
                nodes.add(node);
            }
        }
        if (CollectionUtils.isNotEmpty(dataSourceList)) {
            for (DataSource dataSource : dataSourceList) {
                Node node = new Node();
                node.setData(dataSource);
                node.setId(dataSource.getId());
                node.setType(NodeTypeEnum.DATA_SOURCE.name());
                nodes.add(node);
            }
        }
        return nodes;
    }

    @Override
    public synchronized Long save(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        dataSource.setIdentityColor(DataSourceIdentityColorUtils.normalize(dataSource.getIdentityColor()));
        Long originalId = dataSource.getId();
        DataSource previous = originalId == null ? null : dataMap.get(originalId);
        Long id;
        try {
            id = super.save(dataSource);
        } catch (RuntimeException exception) {
            Long assignedId = dataSource.getId();
            if (assignedId != null) {
                if (previous == null) {
                    dataMap.remove(assignedId);
                } else {
                    dataMap.put(assignedId, previous);
                }
            }
            throw exception;
        }
        createDataSourceNode(id, dataSource.getSpaceId());
        return id;
    }

    @Override
    public synchronized void update(DataSource dataSource) {
        if (dataSource == null || dataSource.getId() == null) {
            return;
        }
        DataSource current = dataMap.get(dataSource.getId());
        if (current == null) {
            return;
        }
        DataSource update = copyDataSource(dataSource);
        update.setIdentityColor(DataSourceIdentityColorUtils.normalize(update.getIdentityColor()));
        DataSource replacement = getAfterSave(copyDataSource(current), update);
        // Full connection updates never own the shared identity color.
        replacement.setIdentityColor(current.getIdentityColor());
        persistReplacement(dataSource.getId(), replacement);
    }

    public synchronized boolean updateIdentityColor(Long id, String identityColor) {
        DataSource current = dataMap.get(id);
        if (current == null) {
            return false;
        }
        DataSource replacement = copyDataSource(current);
        replacement.setIdentityColor(DataSourceIdentityColorUtils.normalize(identityColor));
        persistReplacement(id, replacement);
        return true;
    }

    private void persistReplacement(Long id, DataSource replacement) {
        Map<Long, DataSource> persistedData = new TreeMap<>(dataMap);
        persistedData.put(id, replacement);
        saveDataList(new ArrayList<>(persistedData.values()));
        dataMap.put(id, replacement);
    }

    private DataSource copyDataSource(DataSource source) {
        DataSource copy = new DataSource();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }

    protected synchronized void createDataSourceNode(Long datasourceId, Long namespaceId) {
        Node dropToNode = null;
        if (namespaceId != null && namespaceId > 0) {
            dropToNode = new Node();
            dropToNode.setId(namespaceId);
            dropToNode.setType(NodeTypeEnum.NAMESPACE.name());
        }
        Node node = new Node();
        node.setId(datasourceId);
        node.setType(NodeTypeEnum.DATA_SOURCE.name());
        TreeNodeStorage.INSTANCE.updatePosition(dropToNode, node, 2);
    }

    public void delete(Long id) {
        super.delete(id);
        NamespaceStorage.INSTANCE.deleteDataSourcePosition(id);
        TreeNodeStorage.INSTANCE.deleteNode(Node.builder().id(id).type(NodeTypeEnum.DATA_SOURCE.name()).build());
    }
}
