package ai.chat2db.plugin.sqlite.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.community.domain.api.enums.plugin.DmlTypeEnum;
import ai.chat2db.plugin.sqlite.SqliteSqlGuards;
import ai.chat2db.plugin.sqlite.identifier.SqliteIdentifierProcessor;
import ai.chat2db.plugin.sqlite.enums.type.SqliteColumnTypeEnum;
import ai.chat2db.plugin.sqlite.enums.type.SqliteIndexTypeEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.sqlite.constant.SqliteBuilderConstants.*;
public class SqliteBuilder extends DefaultSqlBuilder {

    @Override
    public String quoteIdentifier(String identifier) {
        return SqliteIdentifierProcessor.INSTANCE.quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .map(SqliteIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                .collect(Collectors.joining(SQLConstants.DOT));
    }

    @Override
    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        script.append(quoteQualifiedIdentifier(databaseName, schemaName, tableName));
    }

    @Override
    protected void buildColumns(List<String> columnList, StringBuilder script) {
        if (CollectionUtils.isNotEmpty(columnList)) {
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS)
                    .append(columnList.stream().map(this::quoteIdentifier)
                            .collect(Collectors.joining(SQLConstants.COMMA)))
                    .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
        }
    }
    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_CREATE_TABLE);
        script.append(quoteQualifiedIdentifier(table.getDatabaseName(), table.getName()))
                .append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            SqliteColumnTypeEnum typeEnum = SqliteColumnTypeEnum.getByType(column.getColumnType());
            if (typeEnum == null) {
                script.append(SQLConstants.TAB).append(buildDefaultCreateColumnSql(column)).append(SQLConstants.COMMA);
                if (StringUtils.isNotBlank(column.getComment())) {
                    script.append(VALUE).append(SqliteSqlGuards.sanitizeLineComment(column.getComment())).append(SQLConstants.SPACE);
                }
                script.append(SQLConstants.LINE_SEPARATOR);
            } else {
                script.append(SQLConstants.TAB).append(typeEnum.buildCreateColumnSql(column)).append(SQLConstants.COMMA);
                if (StringUtils.isNotBlank(column.getComment())) {
                    script.append(VALUE).append(SqliteSqlGuards.sanitizeLineComment(column.getComment())).append(SQLConstants.SPACE);
                }
                script.append(SQLConstants.LINE_SEPARATOR);
            }
        }
        for (TableIndex tableIndex : table.getIndexList()) {
            if (SqliteIndexTypeEnum.PRIMARY_KEY.getName().equals(tableIndex.getType())) {
                SqliteIndexTypeEnum sqliteIndexTypeEnum = SqliteIndexTypeEnum.getByType(tableIndex.getType());
                if (sqliteIndexTypeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(sqliteIndexTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.COMMA_LINE_SEPARATOR);
            }
        }
        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS_SEMICOLON);
        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            if (!SqliteIndexTypeEnum.PRIMARY_KEY.getName().equals(tableIndex.getType())) {
                SqliteIndexTypeEnum sqliteIndexTypeEnum = SqliteIndexTypeEnum.getByType(tableIndex.getType());
                if (sqliteIndexTypeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.LINE_SEPARATOR).append(SQL_CREATE).append(sqliteIndexTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            }
        }
        return script.toString();
    }

    public String buildDefaultCreateColumnSql(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(quoteIdentifier(column.getName())).append(SQLConstants.SPACE);
        script.append(SqliteSqlGuards.requireSafeTypeName(column.getColumnType())).append(SQLConstants.SPACE);

        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQL_ALTER_TABLE)
                    .append(quoteQualifiedIdentifier(oldTable.getDatabaseName(), oldTable.getName()))
                    .append(SQLConstants.LINE_SEPARATOR);
            script.append(SQLConstants.TAB).append(SQL_RENAME).append(quoteIdentifier(newTable.getName()))
                    .append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus()) && StringUtils.isNotBlank(tableColumn.getColumnType()) && StringUtils.isNotBlank(tableColumn.getName())) {
                script.append(SQL_ALTER_TABLE)
                        .append(quoteQualifiedIdentifier(newTable.getDatabaseName(), newTable.getName()))
                        .append(SQLConstants.LINE_SEPARATOR);
                SqliteColumnTypeEnum typeEnum = SqliteColumnTypeEnum.getByType(tableColumn.getColumnType());
                if (typeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(typeEnum.buildModifyColumn(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                SqliteIndexTypeEnum sqliteIndexTypeEnum = SqliteIndexTypeEnum.getByType(tableIndex.getType());
                if (sqliteIndexTypeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(sqliteIndexTypeEnum.buildModifyIndex(tableIndex)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            }
        }

        if (script.length() > 2) {
            script = new StringBuilder(script.substring(0, script.length() - 2));
            script.append(SQLConstants.SEMICOLON);
        }

        return script.toString();
    }

    @Override
    public String buildPageLimit(PageLimitRequest request) {
        String sql = request.getSql();
        int offset = request.getOffset();
        int pageNo = request.getPageNo();
        int pageSize = request.getPageSize();
        return SQL_SELECT_ASTERISK_FROM_OPEN_PAREN + sql + VALUE_CLOSE_PAREN_T_LIMIT + pageSize + SQLConstants.OFFSET_SQL + offset + SQLConstants.EMPTY;
    }

    @Override
    public String buildUpdate(UpdateSqlRequest request) {
        Map<String, String> row = request.getRow();
        Map<String, String> primaryKeyMap = request.getPrimaryKeyMap();
        StringBuilder script = new StringBuilder(SQLConstants.UPDATE_SQL_PREFIX);
        buildTableName(request.getDatabaseName(), request.getSchemaName(), request.getTableName(), script);
        script.append(SQLConstants.SET_SQL);
        script.append(row.entrySet().stream()
                .map(entry -> quoteIdentifier(entry.getKey()) + SQLConstants.EQUAL_SQL + entry.getValue())
                .collect(Collectors.joining(SQLConstants.COMMA)));
        if (MapUtils.isNotEmpty(primaryKeyMap)) {
            script.append(SQLConstants.WHERE_SQL);
            script.append(primaryKeyMap.entrySet().stream()
                    .map(entry -> quoteIdentifier(entry.getKey()) + SQLConstants.EQUAL_SQL + entry.getValue())
                    .collect(Collectors.joining(SQLConstants.SQL_AND)));
        }
        return script.toString();
    }

    @Override
    public String buildTemplate(Table table, String type) {
        if (table == null || CollectionUtils.isEmpty(table.getColumnList()) || StringUtils.isBlank(type)) {
            return SQLConstants.EMPTY;
        }
        String tableName = quoteIdentifier(table.getName());
        List<String> columnNames = table.getColumnList().stream()
                .map(column -> quoteIdentifier(column.getName()))
                .collect(Collectors.toList());
        if (DmlTypeEnum.INSERT.name().equalsIgnoreCase(type)) {
            return SQLConstants.INSERT_INTO_SQL_PREFIX + tableName + SQLConstants.SPACE_OPEN_PARENTHESIS
                    + String.join(SQLConstants.COMMA, columnNames)
                    + SQLConstants.CLOSE_PARENTHESIS + SQLConstants.VALUES_SQL + SQLConstants.OPEN_PARENTHESIS
                    + columnNames.stream().map(name -> SQLConstants.SPACE).collect(Collectors.joining(SQLConstants.COMMA))
                    + SQLConstants.CLOSE_PARENTHESIS;
        }
        if (DmlTypeEnum.UPDATE.name().equalsIgnoreCase(type)) {
            String setClause = columnNames.stream()
                    .map(name -> name + SQLConstants.EQUAL_SQL + SQLConstants.SPACE)
                    .collect(Collectors.joining(SQLConstants.COMMA));
            return SQLConstants.UPDATE_SQL_PREFIX + tableName + SQLConstants.SET_SQL_LOWER
                    + setClause + SQLConstants.WHERE_SQL_LOWER;
        }
        if (DmlTypeEnum.DELETE.name().equalsIgnoreCase(type)) {
            return SQLConstants.DELETE_FROM_SQL_PREFIX + tableName + SQLConstants.WHERE_SQL_LOWER;
        }
        if (DmlTypeEnum.SELECT.name().equalsIgnoreCase(type)) {
            return SQLConstants.SELECT_SQL_PREFIX + String.join(SQLConstants.COMMA, columnNames)
                    + " FROM " + tableName;
        }
        return SQLConstants.EMPTY;
    }
}
