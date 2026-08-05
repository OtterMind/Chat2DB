package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.request.db.DbColumnCommentUpdateRequest;
import ai.chat2db.community.domain.api.model.request.db.DbViewDeleteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbViewMetaModifyRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableDdlRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTablePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.service.db.IDbViewService;
import ai.chat2db.community.domain.core.cache.MemoryCacheManage;
import ai.chat2db.community.domain.core.util.ListSorter;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.sql.Sql;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.ViewMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import cn.hutool.core.map.MapUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.chat2db.community.domain.core.cache.CacheKey.getViewKey;

@Service
public class DbViewServiceImpl implements IDbViewService {

    @Override
    public List<Table> views(String databaseName, String schemaName) {
        List<Table> views = Chat2DBContext.getDbMetaData().views(Chat2DBContext.getConnection(), databaseName, schemaName);
        ListSorter.sortByKey(views, Table::getName);
        return views;
    }

    @Override
    public Table detail(String databaseName, String schemaName, String tableName) {
        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        return metaSchema.view(Chat2DBContext.getConnection(), new ViewMetadataRequest(databaseName, schemaName, tableName));
    }

    @Override
    public String modifySql(ModifyView modifyView) {
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        return sqlBuilder.ddl().view().buildCreateView(modifyView);
    }

    @Override
    public ModifyViewConfiguration meta(DbViewMetaModifyRequest param) {
        String databaseName = param.getDatabaseName();
        String schemaName = param.getSchemaName();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        ModifyViewConfiguration configuration = metaData.viewMeta(databaseName, schemaName);
        return configuration;
    }

    @Override
    public void drop(DbViewDeleteRequest param) {
        IDbManager dbManager = Chat2DBContext.getDbManager();
        dbManager.dropView(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(),
                param.getViewName());
    }

    @Override
    public void create(ModifyView modifyView) {
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        String sql = sqlBuilder.ddl().view().buildCreateView(modifyView);
        ai.chat2db.spi.DefaultSQLExecutor.getInstance().execute(
                Chat2DBContext.getConnection(), sql, resultSet -> null);
    }

    @Override
    public String getAIViewDDL(DbTableDdlRequest param) {
        Table table = query(param);
        if (table == null) {
            return null;
        }
        fillAITableInfo(param, table);
        List<Sql> sqls = buildSql(table);
        StringBuilder sb = new StringBuilder();
        if (CollectionUtils.isNotEmpty(sqls)) {
            sqls.forEach(sql -> sb.append(sql.getSql()).append("\n"));
        }
        return sb.toString();
    }

    @Override
    public Table query(DbTableQueryRequest param) {
        IDbMetaData metaSchema = Chat2DBContext.getDbMetaData();
        List<Table> tables = metaSchema.views(Chat2DBContext.getConnection(),
                new ViewMetadataRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
        if (CollectionUtils.isNotEmpty(tables)) {
            Table table = tables.get(0);
            table.setColumnList(
                    metaSchema.columns(Chat2DBContext.getConnection(),
                            new TableMetadataRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName())));
            return table;
        }
        return null;
    }

    @Override
    public List<SimpleTable> queryViews(DbTablePageQueryRequest queryParam) {
        List<Table> all = getAllViews(queryParam);
        if (CollectionUtils.isEmpty(all)) {
            return new ArrayList<>();
        }
        return all.stream().map(table -> SimpleTable.builder().name(table.getName()).comment(table.getComment()).build()).collect(Collectors.toList());
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

    public List<Sql> buildSql(Table view) {
        ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
        List<Sql> sqls = new ArrayList<>();
        sqls.add(Sql.builder().sql(sqlBuilder.ddl().table().buildAITableSchema(view)).build());
        return sqls;
    }

    private List<Table> getAllViews(DbTablePageQueryRequest param) {
        String key = getViewKey(param.getDataSourceId(), param.getDatabaseName(), param.getSchemaName());
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        List<Table> all;
        if (param.isRefresh()) {
            MemoryCacheManage.remove(key);
            Connection connection = Chat2DBContext.getConnection();
            List<Table> tables = metaData.views(connection,
                    new ViewMetadataRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
            ArrayList<Table> tableList = new ArrayList<>(tables);
            MemoryCacheManage.put(key, tableList);
            all = tableList;
        } else {
            all = MemoryCacheManage.computeIfAbsent(key, () -> {
                Connection connection = Chat2DBContext.getConnection();
                List<Table> tables = metaData.views(connection,
                        new ViewMetadataRequest(param.getDatabaseName(), param.getSchemaName(), param.getTableName()));
                return new ArrayList<>(tables);
            });
        }
        return all;
    }

}
