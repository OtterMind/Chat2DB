package ai.chat2db.community.domain.core.impl.db;


import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.request.db.DbColumnCommentUpdateRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDmlSqlCopyRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableShowCreateRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableDdlRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableCopyRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTablePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.TableSelector;
import ai.chat2db.community.domain.api.model.request.db.DbTypeQueryRequest;
import ai.chat2db.community.domain.api.model.request.pin.DbTablePinRequest;
import ai.chat2db.community.domain.api.model.metadata.extension.MetadataAccessContext;
import ai.chat2db.community.domain.api.service.db.IDbTablePinService;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.domain.core.cache.CacheManage;
import ai.chat2db.community.domain.core.cache.MemoryCacheManage;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.community.domain.core.util.ListSorter;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.enums.plugin.ObjectTypeEnum;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.sql.Sql;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.spi.model.request.*;
import ai.chat2db.spi.model.response.TablesPageResponse;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ai.chat2db.community.domain.core.cache.CacheKey.getColumnKey;
import static ai.chat2db.community.domain.core.cache.CacheKey.getTableKey;


@Service
@Slf4j
public class DbTableServiceImpl implements IDbTableService {

    private final IDbTablePinService tablePinService;
    private final MetadataAccessPolicyManager metadataAccessPolicyManager;

    public DbTableServiceImpl(IDbTablePinService tablePinService) {
        this(tablePinService, new MetadataAccessPolicyManager(List.of()));
    }

    @Autowired
    public DbTableServiceImpl(IDbTablePinService tablePinService,
                              MetadataAccessPolicyManager metadataAccessPolicyManager) {
        this.tablePinService = tablePinService;
        this.metadataAccessPolicyManager = metadataAccessPolicyManager;
    }

    @Override
    public String showCreateTable(DbTableShowCreateRequest param) {
        requireVisibleTable(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(),
                param.getTableName());
        List<TableColumn> rawColumns = rawColumns(param);
        if (!metadataAccessPolicyManager.isEmpty() && rawColumns.isEmpty()) {
            throw new BusinessException("common.permissionDenied");
        }
        if (visibleColumns(param, rawColumns).size() != rawColumns.size()) {
            throw new BusinessException("common.permissionDenied");
        }
        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        return metaSchema.tableDDL(Chat2DBContext.getConnection(),
                new TableMetadataRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
    }

    @Override
    public String createTableExample(String dbType) {
        return Chat2DBContext.getDBConfig(dbType).getSimpleCreateTable();
    }

    @Override
    public String alterTableExample(String dbType) {
        return Chat2DBContext.getDBConfig(dbType).getSimpleAlterTable();
    }

    @Override
    public Table query(DbTableQueryRequest param, TableSelector selector) {
        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        String tableType = param.getTableType();
        if (StringUtils.isNotBlank(tableType) && !ObjectTypeEnum.contains(tableType)) {
            return null;
        }
        if (StringUtils.isBlank(tableType)) {
            tableType = ObjectTypeEnum.TABLE.name();
        }
        ObjectTypeEnum objectTypeEnum = ObjectTypeEnum.from(tableType);
        switch (objectTypeEnum) {
            case VIEW:
                List<Table> views = metaSchema.views(Chat2DBContext.getConnection(),
                        new ViewMetadataRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
                if (CollectionUtils.isNotEmpty(views)) {
                    Table table = views.get(0);
                    table.setColumnList(
                            metaSchema.columns(Chat2DBContext.getConnection(),
                                    new TableMetadataRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName())));
                    return filterTableDetail(param, table);
                }
                break;
            default:
                List<Table> tables = metaSchema.tables(Chat2DBContext.getConnection(),
                        new TablesRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
                if (!CollectionUtils.isEmpty(tables)) {
                    Table table = tables.get(0);
                    table.setIndexList(
                            metaSchema.indexes(Chat2DBContext.getConnection(),
                                    new TableMetadataRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName())));
                    table.setColumnList(
                            metaSchema.columns(Chat2DBContext.getConnection(),
                                    new TableMetadataRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName())));
                    setPrimaryKey(table);
                    return filterTableDetail(param, table);
                }
        }
        return null;

    }

    @Override
    public List<Sql> buildSql(Table oldTable, Table newTable, TableBuilderConfig tableBuilderConfig) {
        initOldTable(oldTable, newTable);
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        List<Sql> sqls = new ArrayList<>();
        if (oldTable == null) {
            initPrimaryKey(newTable);
            sqls.add(Sql.builder().sql(sqlBuilder.ddl().table().buildCreateTable(newTable, tableBuilderConfig)).build());
        } else {
            initUpdatePrimaryKey(oldTable, newTable);
            sqls.add(Sql.builder().sql(sqlBuilder.ddl().table().buildAlterTable(oldTable, newTable)).build());
        }
        return sqls;
    }

    @Override
    public PageResponse<Table> pageQuery(DbTablePageQueryRequest param, TableSelector selector) {
        String dbType = Chat2DBContext.getConnectInfo().getDbType();
        if ("REDIS".equalsIgnoreCase(dbType) && StringUtils.isNoneBlank(param.getSearchKey())) {
            return pageQueryForRedis(param, selector);
        }
        if ("MONGODB".equalsIgnoreCase(dbType)) {
            return pageQueryForMongodb(param);

        }
        List<Table> all = getAllTables(param);
        if (CollectionUtils.isEmpty(all)) {
            return PageResponse.of(new ArrayList<>(), 0L, param.getPageNo(), param.getPageSize());
        }
        List<Table> tables = getQueryTables(all, param);
        tables = visibleTables(param, tables);
        if (CollectionUtils.isEmpty(tables)) {
            return PageResponse.of(new ArrayList<>(), 0L, param.getPageNo(), param.getPageSize());
        }
        long total = tables.size();
        long start = ((long) param.getPageNo() - 1L) * param.getPageSize();
        if (start < 0L || start >= total) {
            return PageResponse.of(Lists.newArrayList(), total, param.getPageNo(), param.getPageSize());
        }
        int startIndex = (int) start;
        int end = (int) Math.min(start + param.getPageSize(), total);
        List<Table> subList = tables.subList(startIndex, end);
        return PageResponse.of(subList, total, param.getPageNo(), param.getPageSize());
    }

    @Override
    public PageResponse<Table> pageQueryWithPinned(DbTablePageQueryRequest param, TableSelector selector) {
        PageResponse<Table> page = pageQuery(param, selector);
        List<Table> tables = page.getData();
        List<Table> orderedTables = addPinnedTables(tables, param);
        return PageResponse.of(orderedTables, page.getTotal(), page.getPageNo(), page.getPageSize());
    }


    @Override
    public List<SimpleTable> queryTables(DbTablePageQueryRequest param) {
        List<Table> all = getAllTables(param);
        if (CollectionUtils.isEmpty(all)) {
            return new ArrayList<>();
        }
        return visibleTables(param, all).stream()
                .map(table -> SimpleTable.builder().name(table.getName()).comment(table.getComment()).build())
                .collect(Collectors.toList());
    }

    @Override
    public List<TableColumn> queryColumns(DbTableQueryRequest param) {
        requireVisibleTable(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(),
                param.getTableName());
        return visibleColumns(param, rawColumns(param));
    }

    @Override
    public List<TableIndex> queryIndexes(DbTableQueryRequest param) {
        requireVisibleTable(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName(),
                param.getTableName());
        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        List<TableIndex> indexes = metaSchema.indexes(Chat2DBContext.getConnection(),
                new TableMetadataRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
        ListSorter.sortByKey(indexes, TableIndex::getName);
        if (CollectionUtils.isEmpty(indexes)) {
            return indexes;
        }
        List<TableColumn> rawColumns = rawColumns(param);
        if (!metadataAccessPolicyManager.isEmpty() && rawColumns.isEmpty() && !indexes.isEmpty()) {
            return List.of();
        }
        List<TableColumn> visibleColumns = visibleColumns(param, rawColumns);
        if (visibleColumns.size() == rawColumns.size()) {
            return indexes;
        }
        return filterIndexes(indexes, normalizedColumnNames(visibleColumns));
    }

    @Override
    public List<TableIndex> queryKeys(DbTableQueryRequest param) {
        return queryIndexes(param);
    }

    private List<Table> addPinnedTables(List<Table> tables, DbTablePageQueryRequest request) {
        if (CollectionUtils.isEmpty(tables)) {
            return tables;
        }
        List<String> pinnedTableNames = tablePinService.queryPinTables(pinRequest(request));
        if (CollectionUtils.isEmpty(pinnedTableNames)) {
            return tables;
        }
        Map<String, Table> tableMap = tables.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Table::getName, Function.identity(), (left, right) -> left));
        List<Table> pinnedTables = new ArrayList<>();
        for (String tableName : pinnedTableNames) {
            Table table = tableMap.get(tableName);
            if (table != null) {
                table.setPinned(true);
                pinnedTables.add(table);
            }
        }
        if (request.getPageNo() == 1 && StringUtils.isBlank(request.getSearchKey())
                && CollectionUtils.isNotEmpty(pinnedTables)) {
            Set<String> pinnedNames = pinnedTables.stream()
                    .map(Table::getName)
                    .collect(Collectors.toSet());
            pinnedTables.addAll(tables.stream()
                    .filter(table -> table == null || !pinnedNames.contains(table.getName()))
                    .collect(Collectors.toList()));
            return pinnedTables;
        }
        return tables;
    }

    private DbTablePinRequest pinRequest(DbTablePageQueryRequest request) {
        DbTablePinRequest param = new DbTablePinRequest();
        param.setUserId(request.getUserId());
        param.setDataSourceId(request.getDataSourceId());
        param.setDatabaseName(request.getDatabaseName());
        param.setSchemaName(request.getSchemaName());
        return param;
    }

    @Override
    public List<Type> queryTypes(DbTypeQueryRequest param) {
        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        return metaSchema.types(Chat2DBContext.getConnection());
    }

    @Override
    public TableMeta queryTableMeta(DbTypeQueryRequest param) {
        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        TableMeta tableMeta = metaSchema.getTableMeta(null, null, null);
        if (tableMeta != null) {
            List<IndexType> indexTypes = tableMeta.getIndexTypes();
            if (CollectionUtils.isNotEmpty(indexTypes)) {
                List<IndexType> types = indexTypes.stream().filter(indexType -> !"Primary".equals(indexType.getTypeName())).collect(Collectors.toList());
                tableMeta.setIndexTypes(types);
            }
        }
        return tableMeta;

    }


    @Override
    public String copyDmlSql(DbDmlSqlCopyRequest param) {
        List<TableColumn> columns = queryColumns(param);
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        Table table = Table.builder().name(param.getTableName()).columnList(columns).build();
        return sqlBuilder.dml().buildTemplate(table, param.getType());
    }

    @Override
    public String getTableDdl(DbTableDdlRequest param) {
        Table table = query(param, new TableSelector());
        if (table == null) {
            return null;
        }
        table.setIndexList(Lists.newArrayList());
        fillAITableInfo(param, table);
        return Chat2DBContext.getSqlBuilder().ddl().table().buildAITableSchema(table);
    }

    @Override
    public void dropTable(DbTableQueryRequest param) {
        try {
            Connection connection = Chat2DBContext.getConnection();
            IDbManager dbManager = Chat2DBContext.getDbManager();
            String sql = dbManager.dropTable(connection, param.getDatabaseName(), param.getSchemaName(),
                    param.getTableName());
            DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
        } catch (Exception e) {
            log.error("drop table error", e);
            throw new BusinessException("drop table error", new Object[]{e.getMessage()}, e);
        }
    }

    @Override
    public void truncateTable(DbTableQueryRequest param) {
        try {
            IDbMetaData metaData = Chat2DBContext.getDbMetaData();
            Connection connection = Chat2DBContext.getConnection();
            String name = metaData.getMetaDataName(param.getTableName());
            String sql = Chat2DBContext.getDbManager()
                    .truncateTable(connection, param.getDatabaseName(), param.getSchemaName(), name);
            DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
        } catch (Exception e) {
            log.error("truncate table error", e);
            throw new BusinessException("truncate table error", new Object[]{e.getMessage()}, e);
        }
    }

    @Override
    public void copyTable(DbTableCopyRequest param) {
        try {
            IDbMetaData metaData = Chat2DBContext.getDbMetaData();
            String newName = param.getNewName();
            if (StringUtils.isBlank(newName)) {
                newName = param.getTableName() + "_copy_" + DateUtil.format(new Date(), "MMddHHmmss");
                if (newName.length() > 32) {
                    newName = newName.substring(0, 32);
                }
            }
            String tableName = metaData.getMetaDataName(param.getTableName());
            String newTableName = metaData.getMetaDataName(newName);
            Chat2DBContext.getDbManager().copyTable(
                    Chat2DBContext.getConnection(),
                    param.getDatabaseName(), param.getSchemaName(), tableName, newTableName, param.isCopyData());
        } catch (Exception e) {
            log.error("copy table error", e);
            throw new BusinessException("copy table error", new Object[]{e.getMessage()}, e);
        }
    }

    private void setPrimaryKey(Table table) {
        if (table == null) {
            return;
        }
        List<TableIndex> tableIndices = table.getIndexList();
        if (CollectionUtils.isEmpty(tableIndices)) {
            return;
        }
        List<TableColumn> columns = table.getColumnList();
        if (CollectionUtils.isEmpty(columns)) {
            return;
        }
        Map<String, TableColumn> columnMap = columns.stream()
                .collect(Collectors.toMap(TableColumn::getName, Function.identity()));
        List<TableIndex> indexes = new ArrayList<>();
        for (TableIndex tableIndex : tableIndices) {
            if ("Primary".equalsIgnoreCase(tableIndex.getType())) {
                List<TableIndexColumn> indexColumns = tableIndex.getColumnList();
                if (CollectionUtils.isNotEmpty(indexColumns)) {
                    for (TableIndexColumn indexColumn : indexColumns) {
                        TableColumn column = columnMap.get(indexColumn.getColumnName());
                        if (column != null) {
                            column.setPrimaryKey(true);
                            column.setPrimaryKeyOrder(indexColumn.getOrdinalPosition());
                            column.setPrimaryKeyName(tableIndex.getName());
                        }
                    }
                }
            } else {
                indexes.add(tableIndex);
            }
        }
        table.setIndexList(indexes);
    }


    private void initOldTable(Table oldTable, Table newTable) {
        if (oldTable == null || newTable == null) {
            return;
        }
        Map<String, TableColumn> columnMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(oldTable.getColumnList())) {
            for (TableColumn column : oldTable.getColumnList()) {
                columnMap.put(column.getName(), column);
            }
        }
        if (CollectionUtils.isNotEmpty(newTable.getColumnList())) {
            for (TableColumn newColumn : newTable.getColumnList()) {
                if (EditStatusEnum.ADD.name().equals(newColumn.getEditStatus())) {
                    continue;
                }
                String name = newColumn.getOldName() == null ? newColumn.getName() : newColumn.getOldName();
                TableColumn oldColumn = columnMap.get(name);
                if (oldColumn != null) {
                    if (oldColumn.equals(newColumn) && EditStatusEnum.MODIFY.name().equals(newColumn.getEditStatus())) {
                        newColumn.setEditStatus(null);
                    } else {
                        newColumn.setOldColumn(oldColumn);
                    }
                }
            }
        }
    }

    private void initPrimaryKey(Table newTable) {
        if (newTable == null) {
            return;
        }
        List<TableColumn> columns = newTable.getColumnList();
        if (CollectionUtils.isEmpty(columns)) {
            return;
        }
        for (TableColumn column : columns) {
            if (column.getPrimaryKey() != null && column.getPrimaryKey()) {
                addPrimaryKey(newTable, column, EditStatusEnum.ADD.name());
            }
        }
    }

    private void initUpdatePrimaryKey(Table oldTable, Table newTable) {
        if (newTable == null || oldTable == null) {
            return;
        }
        List<TableColumn> newColumns = getPrimaryKeyColumn(newTable);
        List<TableColumn> oldColumns = getPrimaryKeyColumn(oldTable);
        if (CollectionUtils.isEmpty(newColumns) && CollectionUtils.isEmpty(oldColumns)) {
            return;
        }
        if (!CollectionUtils.isEmpty(newColumns) && CollectionUtils.isEmpty(oldColumns)) {
            initPrimaryKey(newTable);
            return;
        }
        if (CollectionUtils.isEmpty(newColumns) && CollectionUtils.isNotEmpty(oldColumns)) {
            addPrimaryKey(newTable, oldColumns.get(0), EditStatusEnum.DELETE.name());
            return;
        }
        if (newColumns.size() != oldColumns.size()) {
            for (TableColumn column : newColumns) {
                if (column.getPrimaryKey() != null && column.getPrimaryKey()) {
                    addPrimaryKey(newTable, column, EditStatusEnum.MODIFY.name());
                }
            }
            return;
        }
        boolean flag = false;
        Map<String, TableColumn> oldColumnMap = oldColumns.stream().collect(Collectors.toMap(TableColumn::getName, Function.identity()));
        for (TableColumn column : newColumns) {
            TableColumn oldColumn = oldColumnMap.get(column.getName());
            if (oldColumn == null) {
                flag = true;
            }
        }
        if (flag) {
            for (TableColumn column : newColumns) {
                if (column.getPrimaryKey() != null && column.getPrimaryKey()) {
                    addPrimaryKey(newTable, column, EditStatusEnum.MODIFY.name());
                }
            }
        }
    }

    private PageResponse<Table> pageQueryForRedis(DbTablePageQueryRequest param, TableSelector selector) {
        Connection connection = Chat2DBContext.getConnection();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        TablesPageResponse page = metaData.tables(connection,
                new TablesPageRequest(param.getDatabaseName(), param.getSchemaName(), param.getSearchKey(),
                        param.getPageNo(), param.getPageSize()));
        return filterExternalPage(param,
                PageResponse.of(page.getData(), page.getTotal(), page.getPageNo(), page.getPageSize()));
    }

    private PageResponse<Table> pageQueryForMongodb(DbTablePageQueryRequest param) {
        Connection connection = Chat2DBContext.getConnection();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        TablesPageResponse page = metaData.tables(connection,
                new TablesPageRequest(param.getDatabaseName(), param.getSchemaName(), param.getSearchKey(),
                        param.getPageNo(), param.getPageSize()));
        return filterExternalPage(param,
                PageResponse.of(page.getData(), page.getTotal(), page.getPageNo(), page.getPageSize()));
    }

    private List<Table> getAllTables(DbTablePageQueryRequest param) {
        String key = getTableKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName());
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        List<Table> all;
        if (param.isRefresh()) {
            MemoryCacheManage.remove(key);
            Connection connection = Chat2DBContext.getConnection();
            List<Table> tables = metaData.tables(connection,
                    new TablesRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
            ArrayList<Table> tableList = new ArrayList<>(tables);
            MemoryCacheManage.put(key, tableList);
            all = tableList;
        } else {
            all = MemoryCacheManage.computeIfAbsent(key, () -> {
                Connection connection = Chat2DBContext.getConnection();
                List<Table> tables = metaData.tables(connection,
                        new TablesRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
                ArrayList<Table> tableList = new ArrayList<>(tables);
                return tableList;
            });
        }
        return all;
    }


    private List<Table> getQueryTables(List<Table> all, DbTablePageQueryRequest param) {
        String keyword = param.getSearchKey();
        if (StringUtils.isNotBlank(keyword)) {
            String key = keyword.toLowerCase();
            return all.stream().filter(table ->
                    table.getName().toLowerCase().contains(key) ||
                            (StringUtils.isNotBlank(table.getComment()) && table.getComment().toLowerCase().contains(key))
            ).collect(Collectors.toList());
        } else {
            return all;
        }

    }

    private void fillAITableInfo(DbTableDdlRequest param, Table table) {
        if (StringUtils.isNotBlank(param.getTableCommentAlias())) {
            table.setComment(param.getTableCommentAlias());
        }
        Map<String, String> columnComment = param.getColumnCommentAlias();
        List<DbColumnCommentUpdateRequest> updateRequestColumnAlias = param.getUpdateRequestColumnAlias();
        List<String> excludeColumns = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(updateRequestColumnAlias)) {
            excludeColumns = updateRequestColumnAlias.stream()
                    .filter(column -> Boolean.TRUE.equals(column.getDeletedFlag()))
                    .map(DbColumnCommentUpdateRequest::getColumnName)
                    .toList();
        }
        List<TableColumn> columns = table.getColumnList();
        if (CollectionUtils.isNotEmpty(excludeColumns) && CollectionUtils.isNotEmpty(columns)) {
            List<String> finalExcludeColumns = excludeColumns;
            columns = columns.stream()
                    .filter(column -> !finalExcludeColumns.contains(column.getName()))
                    .collect(Collectors.toList());
            table.setColumnList(columns);
        }
        if (!MapUtil.isEmpty(columnComment)) {
            if (CollectionUtils.isNotEmpty(columns)) {
                columns.forEach(column -> {
                    String alias = columnComment.get(column.getName());
                    if (StringUtils.isNotBlank(alias)) {
                        column.setComment(alias);
                    }
                });
            }
        }
    }

    private List<TableColumn> getPrimaryKeyColumn(Table table) {
        if (table == null || CollectionUtils.isEmpty(table.getColumnList())) {
            return null;
        }
        return table.getColumnList().stream().filter(tableColumn ->
                        tableColumn.getPrimaryKey() != null && tableColumn.getPrimaryKey())
                .collect(Collectors.toList());
    }

    private void addPrimaryKey(Table newTable, TableColumn column, String status) {
        List<TableIndex> indexes = newTable.getIndexList();
        if (indexes == null) {
            indexes = new ArrayList<>();
        }
        TableIndex keyIndex = indexes.stream().filter(index -> "Primary".equalsIgnoreCase(index.getType())).findFirst().orElse(null);
        if (keyIndex == null) {
            keyIndex = new TableIndex();
            keyIndex.setType("Primary");
            keyIndex.setName(StringUtils.isBlank(column.getPrimaryKeyName()) ? "PRIMARY_KEY" : column.getPrimaryKeyName());
            keyIndex.setTableName(newTable.getName());
            keyIndex.setSchemaName(newTable.getSchemaName());
            keyIndex.setDatabaseName(newTable.getDatabaseName());
            keyIndex.setEditStatus(status);
            if (!EditStatusEnum.ADD.name().equals(status)) {
                keyIndex.setOldName(keyIndex.getName());
            }
            indexes.add(keyIndex);
        }
        List<TableIndexColumn> tableIndexColumns = keyIndex.getColumnList();
        if (tableIndexColumns == null) {
            tableIndexColumns = new ArrayList<>();
        }
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName(column.getName());
        indexColumn.setTableName(newTable.getName());
        indexColumn.setSchemaName(newTable.getSchemaName());
        indexColumn.setDatabaseName(newTable.getDatabaseName());
        indexColumn.setOrdinalPosition(Short.valueOf(column.getPrimaryKeyOrder() + ""));
        indexColumn.setEditStatus(status);
        tableIndexColumns.add(indexColumn);
        List<TableIndexColumn> sortTableIndexColumns = tableIndexColumns.stream().sorted(Comparator.comparing(TableIndexColumn::getOrdinalPosition)).collect(Collectors.toList());
        Set<String> statusList = sortTableIndexColumns.stream().map(TableIndexColumn::getEditStatus).collect(Collectors.toSet());
        if (statusList.size() == 1) {
            keyIndex.setEditStatus(statusList.iterator().next());
        } else {
            keyIndex.setEditStatus(EditStatusEnum.MODIFY.name());
        }

        keyIndex.setColumnList(sortTableIndexColumns);
        newTable.setIndexList(indexes);

    }

    private PageResponse<Table> filterExternalPage(DbTablePageQueryRequest request, PageResponse<Table> page) {
        List<Table> original = page.getData();
        List<Table> visible = visibleTables(request, original);
        long safeTotal = visible.size() == original.size() ? page.getTotal() : visible.size();
        return PageResponse.of(visible, safeTotal, page.getPageNo(), page.getPageSize());
    }

    private List<Table> visibleTables(DbTablePageQueryRequest request, List<Table> tables) {
        return metadataAccessPolicyManager.filter(tables,
                table -> resource(request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(),
                        table == null ? null : table.getName(), null));
    }

    private Table filterTableDetail(DbTableQueryRequest request, Table table) {
        requireVisibleTable(request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(),
                request.getTableName());
        List<TableColumn> rawColumns = table.getColumnList() == null ? List.of() : table.getColumnList();
        if (!metadataAccessPolicyManager.isEmpty() && rawColumns.isEmpty()) {
            table.setIndexList(List.of());
            table.setForeignKeyList(List.of());
            table.setDdl(null);
            return table;
        }
        List<TableColumn> visibleColumns = visibleColumns(request, rawColumns);
        table.setColumnList(visibleColumns);
        if (visibleColumns.size() == rawColumns.size()) {
            return table;
        }
        Set<String> visibleNames = normalizedColumnNames(visibleColumns);
        table.setIndexList(filterIndexes(table.getIndexList(), visibleNames));
        table.setForeignKeyList(List.of());
        table.setDdl(null);
        return table;
    }

    private void requireVisibleTable(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        if (!metadataAccessPolicyManager.isAllowed(resource(dataSourceId, databaseName, schemaName, tableName,
                null))) {
            throw new BusinessException("common.permissionDenied");
        }
    }

    private List<TableColumn> rawColumns(DbTableQueryRequest request) {
        return rawColumns(request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(),
                request.getTableName(), request.isRefresh());
    }

    private List<TableColumn> rawColumns(DbTableShowCreateRequest request) {
        return rawColumns(request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(),
                request.getTableName(), false);
    }

    private List<TableColumn> rawColumns(Long dataSourceId, String databaseName, String schemaName, String tableName,
                                         boolean refresh) {
        String tableColumnKey = getColumnKey(dataSourceId, databaseName, schemaName, tableName);
        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        return CacheManage.getList(tableColumnKey, TableColumn.class,
                key -> refresh, key -> metaSchema.columns(Chat2DBContext.getConnection(),
                        new TableMetadataRequest(databaseName, schemaName, tableName)));
    }

    private List<TableColumn> visibleColumns(DbTableQueryRequest request, List<TableColumn> columns) {
        return visibleColumns(request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(),
                request.getTableName(), columns);
    }

    private List<TableColumn> visibleColumns(DbTableShowCreateRequest request, List<TableColumn> columns) {
        return visibleColumns(request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(),
                request.getTableName(), columns);
    }

    private List<TableColumn> visibleColumns(Long dataSourceId, String databaseName, String schemaName,
                                              String tableName, List<TableColumn> columns) {
        return metadataAccessPolicyManager.filter(columns,
                column -> resource(dataSourceId, databaseName, schemaName, tableName,
                        column == null ? null : column.getName()));
    }

    private Set<String> normalizedColumnNames(List<TableColumn> columns) {
        if (CollectionUtils.isEmpty(columns)) {
            return Set.of();
        }
        return columns.stream().map(TableColumn::getName).filter(StringUtils::isNotBlank)
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private List<TableIndex> filterIndexes(List<TableIndex> indexes, Set<String> visibleColumnNames) {
        if (CollectionUtils.isEmpty(indexes)) {
            return indexes;
        }
        List<TableIndex> visible = new ArrayList<>();
        for (TableIndex index : indexes) {
            if (index == null || CollectionUtils.isEmpty(index.getColumnList())) {
                continue;
            }
            List<TableIndexColumn> columns = index.getColumnList().stream()
                    .filter(Objects::nonNull)
                    .filter(column -> StringUtils.isNotBlank(column.getColumnName()))
                    .filter(column -> visibleColumnNames.contains(column.getColumnName().toLowerCase(Locale.ROOT)))
                    .toList();
            if (columns.isEmpty()) {
                continue;
            }
            TableIndex copy = copyIndex(index);
            copy.setColumnList(columns);
            copy.setForeignColumnNamelist(List.of());
            visible.add(copy);
        }
        return visible;
    }

    private TableIndex copyIndex(TableIndex source) {
        TableIndex copy = new TableIndex();
        copy.setOldName(source.getOldName());
        copy.setName(source.getName());
        copy.setTableName(source.getTableName());
        copy.setType(source.getType());
        copy.setUnique(source.getUnique());
        copy.setComment(source.getComment());
        copy.setSchemaName(source.getSchemaName());
        copy.setDatabaseName(source.getDatabaseName());
        copy.setColumnList(source.getColumnList());
        copy.setEditStatus(source.getEditStatus());
        copy.setConcurrently(source.getConcurrently());
        copy.setMethod(source.getMethod());
        copy.setForeignSchemaName(source.getForeignSchemaName());
        copy.setForeignTableName(source.getForeignTableName());
        copy.setForeignColumnNamelist(source.getForeignColumnNamelist());
        return copy;
    }

    private MetadataAccessContext resource(Long dataSourceId, String databaseName, String schemaName,
            String tableName, String columnName) {
        String dbType = Chat2DBContext.getConnectInfo() == null ? null : Chat2DBContext.getConnectInfo().getDbType();
        return MetadataAccessContext.builder()
                .dataSourceId(dataSourceId)
                .dbType(dbType)
                .databaseName(databaseName)
                .schemaName(schemaName)
                .tableName(tableName)
                .columnName(columnName)
                .operationType("SELECT")
                .build();
    }
}
