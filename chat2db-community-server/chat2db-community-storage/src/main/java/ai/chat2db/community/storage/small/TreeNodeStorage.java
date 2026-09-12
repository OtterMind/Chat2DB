package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.enums.NodeTypeEnum;
import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.domain.api.model.db.TreeNode;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.filter.PropertyFilter;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Slf4j
public class TreeNodeStorage extends SmallDataStorage<TreeNode> {
    public static final TreeNodeStorage INSTANCE = new TreeNodeStorage();

    protected TreeNodeStorage() {
        super("tree", TreeNode.class);
        if (MapUtils.isEmpty(dataMap)) {

        }
    }

    TreeNodeStorage(File storageFile) {
        super(storageFile, TreeNode.class);
    }

    public synchronized List<Node> getNodes() {
        List<TreeNode> treeNodes = getDataList();
        if (treeNodes == null) {
            return null;
        }
        if (CollectionUtils.isEmpty(treeNodes)) {
            return Lists.newArrayList();
        }
        return treeNodes.get(0).getChildren();
    }

    public synchronized void createTree(List<Node> nodes) {
        if (nodes == null) {
            return;
        }
        PropertyFilter filter = (object, name, value) -> !"data".equals(name);
        List<Node> newNodes = copyNodes(nodes, filter);

        List<TreeNode> treeNodes = getDataList();
        if (CollectionUtils.isEmpty(treeNodes)) {
            TreeNode treeNode = new TreeNode();
            treeNode.setId(generateId());
            treeNode.setChildren(newNodes);
            persistTree(treeNode);
        } else {
            TreeNode replacement = new TreeNode();
            replacement.setId(treeNodes.get(0).getId());
            replacement.setChildren(newNodes);
            persistTree(replacement);
        }
    }

    private void persistTree(TreeNode replacement) {
        Map<Long, TreeNode> persistedData = new TreeMap<>(dataMap);
        persistedData.put(replacement.getId(), replacement);
        saveDataList(new ArrayList<>(persistedData.values()));
        dataMap.put(replacement.getId(), replacement);
    }


    public synchronized ActionResult updatePosition(Node dropToNode, Node dragNode, Integer dropPosition) {
        if (dragNode == null) {
            return ActionResult.isSuccess();
        }
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Node> nodes = getNodes();
        if (nodes == null) {
            return ActionResult.isSuccess();
        }
        PropertyFilter filter = (object, name, value) -> !"data".equals(name);
        List<Node> updatedNodes = copyNodes(nodes, filter);
        Node sourceNode = findNode(updatedNodes, dragNode);
        if (sourceNode == null) {
            return ActionResult.isSuccess();
        }
        if (dropToNode == null) {
            removeNode(updatedNodes, sourceNode, false);
            updatedNodes.add(sourceNode);
            createTree(updatedNodes);
            return ActionResult.isSuccess();
        }
        Node targetNode = findNode(updatedNodes, dropToNode);
        if (targetNode == null || sameNode(sourceNode, targetNode)
                || findNode(sourceNode.getChildren(), targetNode) != null) {
            return ActionResult.isSuccess();
        }
        if (!removeNode(updatedNodes, sourceNode, false)
                || !addNode(updatedNodes, targetNode, sourceNode, dropPosition)) {
            return ActionResult.isSuccess();
        }
        createTree(updatedNodes);
        return ActionResult.isSuccess();
    }

    private List<Node> copyNodes(List<Node> nodes, PropertyFilter filter) {
        String json = JSON.toJSONString(nodes, filter);
        return JSON.parseArray(json, Node.class);
    }

    private synchronized Node findNode(List<Node> nodes, Node expected) {
        if (CollectionUtils.isEmpty(nodes) || expected == null) {
            return null;
        }
        for (Node node : nodes) {
            if (sameNode(node, expected)) {
                return node;
            }
            Node child = findNode(node.getChildren(), expected);
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    private boolean sameNode(Node left, Node right) {
        return left != null && right != null
                && Objects.equals(left.getId(), right.getId())
                && Objects.equals(left.getType(), right.getType());
    }

    private synchronized boolean removeNode(List<Node> nodes, Node dragNode, boolean deleteChildren) {
        if (CollectionUtils.isEmpty(nodes)) {
            return false;
        }
        Iterator<Node> iterator = nodes.iterator();
        List<Node> tempList = new ArrayList<>();
        while (iterator.hasNext()) {
            Node node = iterator.next();
            if (sameNode(node, dragNode)) {
                if (NodeTypeEnum.NAMESPACE.name().equals(node.getType())) {
                    List<Node> c = node.getChildren();
                    if (CollectionUtils.isNotEmpty(c)) {
                        tempList.addAll(c);
                        dragNode.setChildren(c);
                    }
                }
                iterator.remove();
                if (CollectionUtils.isNotEmpty(tempList) && deleteChildren) {
                    nodes.addAll(tempList);
                }
                return true;
            }
            if (removeNode(node.getChildren(), dragNode, deleteChildren)) {
                return true;
            }
        }
        return false;
    }

    public synchronized ActionResult deleteNode(Node dragNode) {
        List<Node> nodes = getNodes();
        if (nodes == null || dragNode == null) {
            return ActionResult.isSuccess();
        }
        PropertyFilter filter = (object, name, value) -> !"data".equals(name);
        List<Node> updatedNodes = copyNodes(nodes, filter);
        if (!removeNode(updatedNodes, dragNode, true)) {
            return ActionResult.isSuccess();
        }
        createTree(updatedNodes);
        return ActionResult.isSuccess();
    }

    private synchronized boolean addNode(List<Node> nodes, Node dropToNode, Node dragNode, Integer dropToGap) {
        if (CollectionUtils.isEmpty(nodes)) {
            return false;
        }
        if (sameNode(dropToNode, dragNode)) {
            return false;
        }
        int index = 0;
        for (Node node : nodes) {
            index++;
            if (sameNode(node, dropToNode)) {
                if (dropToGap == 0) {
                    List<Node> children = node.getChildren();
                    if (children == null) {
                        children = new ArrayList<>();
                    }
                    children.add(0, dragNode);
                    node.setChildren(children);
                    return true;
                }else if (dropToGap == 2) {
                    List<Node> children = node.getChildren();
                    if (children == null) {
                        children = new ArrayList<>();
                    }
                    children.add(dragNode);
                    node.setChildren(children);
                    return true;
                }else if (dropToGap == 1) {
                    nodes.add(index, dragNode);
                    return true;
                } else {
                    nodes.add(index - 1, dragNode);
                    return true;
                }
            }
            if (addNode(node.getChildren(), dropToNode, dragNode, dropToGap)) {
                return true;
            }
        }
        return false;
    }
}
