package ai.chat2db.server.domain.core.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import ai.chat2db.server.domain.api.param.CreateVirtualFKParam;
import ai.chat2db.server.domain.api.param.ErDiagramQueryParam;
import ai.chat2db.server.domain.api.param.TablePageQueryParam;
import ai.chat2db.server.domain.api.param.TableSelector;
import ai.chat2db.server.domain.api.service.ErDiagramService;
import ai.chat2db.server.domain.api.service.ForeignKeySyncService;
import ai.chat2db.server.domain.api.service.TableService;
import ai.chat2db.server.domain.api.vo.InferVirtualFkResultVO;
import ai.chat2db.spi.model.ErDiagram;
import ai.chat2db.spi.model.ForeignKey;
import ai.chat2db.spi.model.Table;
import ai.chat2db.spi.model.TableIndex;
import ai.chat2db.spi.model.TableIndexColumn;
import ai.chat2db.spi.model.TableColumn;
import ai.chat2db.spi.model.VirtualForeignKey;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ErDiagramServiceImpl implements ErDiagramService {

    @Autowired
    private TableService tableService;

    @Autowired
    private ForeignKeySyncService foreignKeySyncService;

    @Override
    public ErDiagram queryErDiagram(ErDiagramQueryParam param) {
        if (Boolean.TRUE.equals(param.getSyncForeignKeys())) {
            foreignKeySyncService.syncForeignKeys(
                    param.getDataSourceId(),
                    param.getDatabaseName(),
                    param.getSchemaName(),
                    null
            );
        }
        
        List<Table> tables = queryTables(param);
        List<ErDiagram.Node> nodes = buildNodes(tables);
        Set<String> tableNameSet = tables.stream().map(Table::getName).collect(Collectors.toSet());
        boolean includeVirtual = param.getIncludeVirtualFk() == null || param.getIncludeVirtualFk();
        List<ErDiagram.Edge> edges = buildEdges(tables, tableNameSet, includeVirtual);
        
        if (Boolean.TRUE.equals(param.getOnlyRelatedTables())) {
            Set<String> relatedTableIds = edges.stream()
                    .flatMap(e -> java.util.stream.Stream.of(e.getSource(), e.getTarget()))
                    .collect(Collectors.toSet());
            nodes = nodes.stream()
                    .filter(n -> relatedTableIds.contains(n.getId()))
                    .collect(Collectors.toList());
            edges = edges.stream()
                    .filter(e -> relatedTableIds.contains(e.getSource()) && relatedTableIds.contains(e.getTarget()))
                    .collect(Collectors.toList());
        }
        
        return ErDiagram.builder().nodes(nodes).edges(edges).build();
    }

    private List<Table> queryTables(ErDiagramQueryParam param) {
        TablePageQueryParam tablePageQueryParam = TablePageQueryParam.builder()
                .dataSourceId(param.getDataSourceId())
                .databaseName(param.getDatabaseName())
                .schemaName(param.getSchemaName())
                .searchKey(param.getTableNameFilter())
                .build();
        TableSelector selector = new TableSelector();
        selector.setColumnList(true);
        selector.setIndexList(true);
        selector.setForeignKey(true);
        return tableService.pageQuery(tablePageQueryParam, selector);
    }

    private List<ErDiagram.Node> buildNodes(List<Table> tables) {
        return tables.stream().map(table -> ErDiagram.Node.builder()
                .id(table.getName())
                .name(table.getName())
                .comment(table.getComment())
                .columnCount(CollectionUtils.isEmpty(table.getColumnList()) ? 0 : table.getColumnList().size())
                .build()
        ).collect(Collectors.toList());
    }

    private List<ErDiagram.Edge> buildEdges(List<Table> tables, Set<String> tableNameSet, boolean includeVirtual) {
        List<ErDiagram.Edge> edges = new ArrayList<>();
        for (Table table : tables) {
            if (CollectionUtils.isNotEmpty(table.getForeignKeyList())) {
                for (ForeignKey fk : table.getForeignKeyList()) {
                    if (tableNameSet.contains(fk.getReferencedTable())) {
                        edges.add(buildEdge(table.getName(), fk, false));
                    }
                }
            }
            if (includeVirtual && CollectionUtils.isNotEmpty(table.getVirtualForeignKeyList())) {
                for (VirtualForeignKey vfk : table.getVirtualForeignKeyList()) {
                    if (tableNameSet.contains(vfk.getReferencedTable())) {
                        edges.add(buildEdge(table.getName(), vfk, true));
                    }
                }
            }
        }
        return edges;
    }

    private ErDiagram.Edge buildEdge(String tableName, ForeignKey fk, boolean virtual) {
        String defaultId = virtual
                ? "VFK_" + tableName + "_" + fk.getColumn()
                : tableName + "_" + fk.getColumn() + "_" + fk.getReferencedTable();
        return ErDiagram.Edge.builder()
                .id(fk.getName() != null ? fk.getName() : defaultId)
                .source(tableName)
                .target(fk.getReferencedTable())
                .sourceColumn(fk.getColumn())
                .targetColumn(fk.getReferencedColumn())
                .label(fk.getColumn() + " -> " + fk.getReferencedColumn())
                .virtual(virtual)
                .build();
    }

    /**
     * 推断虚拟外键关系
     * 基于列命名约定自动发现表之间的潜在外键关联，并同步到虚拟外键存储中
     *
     * @param param ER图查询参数，包含数据源、数据库/模式、表过滤等
     * @return 推断结果，包含新增和删除的虚拟外键信息及数量
     */
    @Override
    public InferVirtualFkResultVO inferVirtualForeignKeys(ErDiagramQueryParam param) {
        // 1. 获取当前数据源/库/模式下已存在的所有虚拟外键
        List<VirtualForeignKey> existingVirtualForeignKeys = foreignKeySyncService.queryAllVirtualForeignKeys(
                param.getDataSourceId(),
                param.getDatabaseName(),
                param.getSchemaName()
        );

        // 2. 查询需要参与推断的表列表（包含列和索引信息）
        List<Table> tables = queryTablesForInference(param);

        // 3. 提取有效表名列表，用于后续清理无效虚拟外键
        List<String> existingTableNames = tables.stream()
                .map(Table::getName)
                .collect(Collectors.toList());

        // 4. 构建规范化后的表名->表对象映射，用于快速查找
        Map<String, Table> tableMap = tables.stream()
                .filter(table -> table.getName() != null)
                .collect(Collectors.toMap(
                        table -> normalizeName(table.getName()),
                        table -> table,
                        (first, second) -> first
                ));
        Map<String, Table> targetTableCache = new HashMap<>(tableMap);

        // 5. 判断是否需要清理无效虚拟外键：当没有表名过滤条件时才执行清理
        boolean cleanInvalidVirtualForeignKeys = param.getTableNameFilter() == null
                || param.getTableNameFilter().isBlank();
        // 6. 筛选出指向不存在表的无效虚拟外键（关联的表或引用表已不在当前表集合中）
        // 复用推断开始时的虚拟外键快照，避免后续按表重复查询 H2；有表过滤时不做清理，防止误删过滤范围外的外键。
        List<VirtualForeignKey> invalidVirtualForeignKeys = cleanInvalidVirtualForeignKeys
                ? existingVirtualForeignKeys.stream()
                        .filter(vfk -> !tableMap.containsKey(normalizeName(vfk.getTableName()))
                                || !tableMap.containsKey(normalizeName(vfk.getReferencedTable())))
                        .collect(Collectors.toList())
                : Collections.emptyList();

        // 7. 执行清理操作，删除指向已不存在表的虚拟外键记录
        int cleanedCount = cleanInvalidVirtualForeignKeys
                ? foreignKeySyncService.cleanInvalidVirtualForeignKeys(
                        param.getDataSourceId(),
                        param.getDatabaseName(),
                        param.getSchemaName(),
                        existingTableNames
                )
                : 0;
        log.info("Cleaned {} invalid virtual foreign keys before inference", cleanedCount);

        // 8. 过滤出仍然有效的虚拟外键（排除已被标记为无效的）
        List<VirtualForeignKey> validVirtualForeignKeys = existingVirtualForeignKeys.stream()
                .filter(vfk -> !invalidVirtualForeignKeys.contains(vfk))
                .collect(Collectors.toList());
        // 9. 按表名分组有效虚拟外键，用于后续跳过已有虚拟外键的表
        Map<String, List<VirtualForeignKey>> virtualForeignKeysByTable = validVirtualForeignKeys.stream()
                .filter(vfk -> vfk.getTableName() != null)
                .collect(Collectors.groupingBy(vfk -> normalizeName(vfk.getTableName())));

        // 10. 收集已有虚拟外键的表名集合，避免重复推断
        Set<String> tablesWithVirtualForeignKeys = validVirtualForeignKeys.stream()
                .map(VirtualForeignKey::getTableName)
                .filter(Objects::nonNull)
                .map(this::normalizeName)
                .collect(Collectors.toSet());

        // 11. 遍历所有表，对尚未有虚拟外键的表进行推断
        List<CreateVirtualFKParam> inferredCreateParams = new ArrayList<>();
        for (Table table : tables) {
            // 跳过已有虚拟外键的表
            if (table.getName() != null
                    && tablesWithVirtualForeignKeys.contains(normalizeName(table.getName()))) {
                continue;
            }
            // 基于列命名约定推断该表的潜在外键关系
            List<VirtualForeignKey> inferredFKs = findVirtualForeignKeys(
                    table,
                    targetTableCache,
                    param,
                    virtualForeignKeysByTable.getOrDefault(normalizeName(table.getName()), Collections.emptyList())
            );
            for (VirtualForeignKey vfk : inferredFKs) {
                inferredCreateParams.add(CreateVirtualFKParam.builder()
                        .dataSourceId(param.getDataSourceId())
                        .databaseName(param.getDatabaseName())
                        .schemaName(param.getSchemaName())
                        .tableName(table.getName())
                        .columnName(vfk.getColumn())
                        .referencedTable(vfk.getReferencedTable())
                        .referencedColumnName(vfk.getReferencedColumn())
                        .comment("Inferred from column naming convention")
                        .sourceType("INFERRED")
                        .build());
            }
        }
        List<VirtualForeignKey> addedList = foreignKeySyncService.createInferredVirtualFKs(inferredCreateParams);

        // 12. 构建删除项结果列表（被清理的无效虚拟外键）
        List<InferVirtualFkResultVO.VirtualFkItem> deletedItems = invalidVirtualForeignKeys.stream()
                .map(vfk -> InferVirtualFkResultVO.VirtualFkItem.builder()
                        .tableName(vfk.getTableName())
                        .columnName(vfk.getColumn())
                        .referencedTable(vfk.getReferencedTable())
                        .referencedColumnName(vfk.getReferencedColumn())
                        .build())
                .collect(Collectors.toList());

        // 13. 构建新增项结果列表（本次推断成功创建的虚拟外键）
        List<InferVirtualFkResultVO.VirtualFkItem> addedItems = addedList.stream()
                .map(vfk -> InferVirtualFkResultVO.VirtualFkItem.builder()
                        .tableName(vfk.getTableName())
                        .columnName(vfk.getColumn())
                        .referencedTable(vfk.getReferencedTable())
                        .referencedColumnName(vfk.getReferencedColumn())
                        .build())
                .collect(Collectors.toList());

        // 14. 组装并返回推断结果
        InferVirtualFkResultVO result = InferVirtualFkResultVO.builder()
                .addedCount(addedItems.size())
                .deletedCount(deletedItems.size())
                .added(addedItems)
                .deleted(deletedItems)
                .build();

        return result;
    }

    /**
     * 查询用于推断的表列表
     * 获取指定数据源/数据库/模式下的表信息（包含列和索引），
     * 并加载真实外键列表回填到表对象中
     *
     * @param param ER图查询参数
     * @return 包含列和真实外键信息的表列表
     */
    private List<Table> queryTablesForInference(ErDiagramQueryParam param) {
        // 1. 构建表分页查询参数
        TablePageQueryParam tablePageQueryParam = TablePageQueryParam.builder()
                .dataSourceId(param.getDataSourceId())
                .databaseName(param.getDatabaseName())
                .schemaName(param.getSchemaName())
                .searchKey(param.getTableNameFilter())
                .build();
        // 2. 设置选择器，需要获取列列表和索引列表
        TableSelector selector = new TableSelector();
        selector.setColumnList(true);
        selector.setIndexList(true);

        // 3. 执行分页查询获取表列表
        List<Table> tables = tableService.pageQuery(tablePageQueryParam, selector);
        // 4. 查询该数据源/库/模式下的所有真实物理外键
        List<ForeignKey> realForeignKeys = foreignKeySyncService.queryRealForeignKeys(
                param.getDataSourceId(),
                param.getDatabaseName(),
                param.getSchemaName(),
                null
        );
        // 5. 按表名分组真实外键，便于后续快速查找
        Map<String, List<ForeignKey>> realForeignKeysByTable = realForeignKeys.stream()
                .filter(fk -> fk.getTableName() != null)
                .collect(Collectors.groupingBy(fk -> normalizeName(fk.getTableName())));

        // 6. 将真实外键批量回填到对应的表对象中
        // 真实外键一次批量加载后回填到表对象；虚拟外键由调用方的全量快照处理。
        for (Table table : tables) {
            table.setForeignKeyList(realForeignKeysByTable.getOrDefault(
                    normalizeName(table.getName()),
                    Collections.emptyList()
            ));
        }
        return tables;
    }

    /**
     * 发现可能的虚拟外键关系（根据命名规范推断）
     * 参考 TableServiceImpl.findVirtualForeignKeys 实现
     */
    private List<VirtualForeignKey> findVirtualForeignKeys(Table table,
                                                           Map<String, Table> targetTableCache,
                                                           ErDiagramQueryParam param,
                                                           List<VirtualForeignKey> storedVirtualFKs) {
        List<VirtualForeignKey> result = new ArrayList<>();

        // 预加载已明确声明的外键列名（用于排除已存在的外键）
        Set<String> explicitForeignKeys = defaultList(table.getForeignKeyList()).stream()
                .map(ForeignKey::getColumn)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        // 排除唯一索引列
        if (CollectionUtils.isNotEmpty(table.getIndexList())) {
            table.getIndexList().stream()
                    .filter(index -> Boolean.TRUE.equals(index.getUnique()))
                    .map(TableIndex::getColumnList)
                    .flatMap(List::stream)
                    .map(TableIndexColumn::getColumnName)
                    .forEach(explicitForeignKeys::add);
        }

        // 调用方传入预加载的虚拟外键，避免每张表单独查询一次 H2。
        Set<String> existingVirtualFKColumns = storedVirtualFKs.stream()
                .map(VirtualForeignKey::getColumn)
                .collect(Collectors.toSet());

        // 筛选候选列
        if (CollectionUtils.isNotEmpty(table.getColumnList())) {
            for (TableColumn column : table.getColumnList()) {
                if (isPotentialVirtualKeyCandidate(column)
                        && !explicitForeignKeys.contains(column.getName())
                        && !existingVirtualFKColumns.contains(column.getName())) {
                    VirtualForeignKey vfk = analyzeColumnRelation(table, column, targetTableCache, param);
                    if (vfk != null) {
                        result.add(vfk);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 判断列是否为虚拟外键候选列
     */
    private boolean isPotentialVirtualKeyCandidate(TableColumn column) {
        return column.getName() != null
                && column.getName().endsWith("_id")
                && Boolean.FALSE.equals(column.getPrimaryKey())
                && column.getName().length() > 3;
    }

    /**
     * 分析列关联关系并构建虚拟外键
     */
    private VirtualForeignKey analyzeColumnRelation(Table currentTable,
                                                    TableColumn currentColumn,
                                                    Map<String, Table> targetTableCache,
                                                    ErDiagramQueryParam param) {
        String columnName = currentColumn.getName();
        String referencedTableName = columnName.substring(0, columnName.length() - 3);
        String currentTableName = currentTable.getName();

        // 排除自关联
        if (referencedTableName.equalsIgnoreCase(currentTableName)) {
            return null;
        }

        // 先从已加载表中匹配；若当前请求有过滤导致目标表缺失，则按引用表名缓存一次补查结果。
        Table targetTable = resolveTargetTable(currentTable, currentColumn, referencedTableName, targetTableCache, param);
        if (targetTable == null) {
            return null;
        }

        // 查找目标表的关联列
        String referencedColumnName = "id";
        if (CollectionUtils.isNotEmpty(targetTable.getColumnList())) {
            for (TableColumn tableColumn : targetTable.getColumnList()) {
                if (columnName.equalsIgnoreCase(tableColumn.getName())) {
                    referencedColumnName = tableColumn.getName();
                    break;
                } else if ("id".equals(referencedColumnName) && Boolean.TRUE.equals(tableColumn.getPrimaryKey())) {
                    referencedColumnName = tableColumn.getName();
                }
            }
        }

        return VirtualForeignKey.builder()
                .name(String.format("VFK_%s_%s", currentTableName, columnName))
                .tableName(currentTableName)
                .column(columnName)
                .referencedTable(targetTable.getName())
                .referencedColumn(referencedColumnName)
                .virtualProperty("Inferred from column naming convention")
                .build();
    }

    private Table resolveTargetTable(Table currentTable,
                                     TableColumn currentColumn,
                                     String referencedTableName,
                                     Map<String, Table> targetTableCache,
                                     ErDiagramQueryParam param) {
        String cacheKey = normalizeName(referencedTableName);
        if (targetTableCache.containsKey(cacheKey)) {
            return targetTableCache.get(cacheKey);
        }

        TableSelector selector = new TableSelector();
        selector.setColumnList(true);
        List<Table> tables = tableService.pageQuery(
                TablePageQueryParam.builder()
                        .dataSourceId(param.getDataSourceId())
                        .databaseName(currentColumn.getDatabaseName())
                        .schemaName(currentColumn.getSchemaName())
                        .searchKey(referencedTableName)
                        .build(),
                selector
        );
        Table targetTable = tables.stream()
                .filter(t -> currentTable.getName() == null || !currentTable.getName().equalsIgnoreCase(t.getName()))
                .findFirst()
                .orElse(null);
        targetTableCache.put(cacheKey, targetTable);
        return targetTable;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private <T> List<T> defaultList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
