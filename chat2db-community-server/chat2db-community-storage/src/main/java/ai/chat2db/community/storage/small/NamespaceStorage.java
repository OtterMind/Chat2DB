package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.enums.NodeTypeEnum;
import ai.chat2db.community.domain.api.model.workspace.Namespace;
import ai.chat2db.community.domain.api.model.workspace.Node;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.collections4.CollectionUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

public class NamespaceStorage extends SmallDataStorage<Namespace> {

    public static final NamespaceStorage INSTANCE = new NamespaceStorage();

    protected NamespaceStorage() {
        super("namespace", Namespace.class);
    }

    NamespaceStorage(File storageFile) {
        super(storageFile, Namespace.class);
    }


    public synchronized void deleteDataSourcePosition(Long dataSourceId) {
        Map<Long, Namespace> candidateDataMap = copyDataMap();
        for (Namespace namespace : candidateDataMap.values()) {
            List<Long> dataSourceIds = namespace.getDatasourceIds();
            if (!CollectionUtils.isEmpty(dataSourceIds)) {
                if (dataSourceIds.contains(dataSourceId)) {
                    dataSourceIds.remove(dataSourceId);
                }
            }
        }
        persistPositions(candidateDataMap);
    }

    public synchronized void updateDataSourcePosition(Long namespaceId, Long dataSourceId) {
        Map<Long, Namespace> candidateDataMap = copyDataMap();
        for (Namespace namespace : candidateDataMap.values()) {
            List<Long> dataSourceIds = namespace.getDatasourceIds();
            if (!CollectionUtils.isEmpty(dataSourceIds)) {
                if (dataSourceIds.contains(dataSourceId)) {
                    dataSourceIds.remove(dataSourceId);
                }
            }
        }
        Namespace namespace = namespaceId == null ? null : candidateDataMap.get(namespaceId);
        if (namespace != null) {
            List<Long> dataSourceIds = namespace.getDatasourceIds();
            if (CollectionUtils.isEmpty(dataSourceIds)) {
                dataSourceIds = new ArrayList<>();
                dataSourceIds.add(dataSourceId);
                namespace.setDatasourceIds(dataSourceIds);
            } else {
                if (!dataSourceIds.contains(dataSourceId)) {
                    dataSourceIds.add(dataSourceId);
                }
            }
        }
        persistPositions(candidateDataMap);
    }

    private Map<Long, Namespace> copyDataMap() {
        Map<Long, Namespace> candidateDataMap = new ConcurrentSkipListMap<>();
        dataMap.forEach((id, namespace) -> candidateDataMap.put(id,
                JSON.parseObject(JSON.toJSONString(namespace), Namespace.class)));
        return candidateDataMap;
    }

    private void persistPositions(Map<Long, Namespace> candidateDataMap) {
        saveDataList(new ArrayList<>(candidateDataMap.values()));
        dataMap = candidateDataMap;
    }

    public Long save(Namespace namespace){
        Long id = super.save(namespace);
        Node dropToNode = null;
        if (namespace.getParentId() != null) {
            dropToNode = new Node();
            dropToNode.setId(namespace.getParentId());
            dropToNode.setType(NodeTypeEnum.NAMESPACE.name());
        }
        Node node = new Node();
        node.setId(namespace.getId());
        node.setType(NodeTypeEnum.NAMESPACE.name());
        TreeNodeStorage.INSTANCE.updatePosition(dropToNode, node, 2);
        return id;
    }
    public void delete(Long id) {
        super.delete(id);
        TreeNodeStorage.INSTANCE.deleteNode(Node.builder().id(id).type(NodeTypeEnum.NAMESPACE.name()).build());
    }
}
