package ai.chat2db.plugin.sqlite.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.sqlite.SqliteMetaData;
import ai.chat2db.plugin.sqlite.enums.type.SqliteColumnTypeEnum;
import ai.chat2db.plugin.sqlite.enums.type.SqliteIndexTypeEnum;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.lang3.StringUtils;


import static ai.chat2db.plugin.sqlite.constant.SqliteBuilderConstants.*;
public class SqliteBuilder extends DefaultSqlBuilder {







    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_CREATE_TABLE);
        if (StringUtils.isNotBlank(table.getDatabaseName())) {
            script.append(SQLConstants.DOUBLE_QUOTE).append(table.getDatabaseName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE);
        }
        script.append(SQLConstants.DOUBLE_QUOTE).append(table.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            SqliteColumnTypeEnum typeEnum = SqliteColumnTypeEnum.getByType(column.getColumnType());
            if (typeEnum == null) {
                script.append(SQLConstants.TAB).append(buildDefaultCreateColumnSql(column)).append(SQLConstants.COMMA);
                if (StringUtils.isNotBlank(column.getComment())) {
                    script.append(VALUE).append(column.getComment()).append(SQLConstants.SPACE);
                }
                script.append(SQLConstants.LINE_SEPARATOR);
            } else {
                script.append(SQLConstants.TAB).append(typeEnum.buildCreateColumnSql(column)).append(SQLConstants.COMMA);
                if (StringUtils.isNotBlank(column.getComment())) {
                    script.append(VALUE).append(column.getComment()).append(SQLConstants.SPACE);
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
        script.append(SQLConstants.DOUBLE_QUOTE).append(column.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.SPACE);
        script.append(column.getColumnType()).append(SQLConstants.SPACE);

        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQL_ALTER_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(oldTable.getDatabaseName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(oldTable.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.LINE_SEPARATOR);
            script.append(SQLConstants.TAB).append(SQL_RENAME).append(SQLConstants.DOUBLE_QUOTE).append(newTable.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus()) && StringUtils.isNotBlank(tableColumn.getColumnType()) && StringUtils.isNotBlank(tableColumn.getName())) {
                script.append(SQL_ALTER_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(newTable.getDatabaseName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(newTable.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.LINE_SEPARATOR);
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
    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        ISQLIdentifierProcessor sqliteIdentifierProcessor = SqliteMetaData.SQLITE_IDENTIFIER_PROCESSOR;
        if (StringUtils.isNotBlank(databaseName)) {
            script.append(sqliteIdentifierProcessor.quoteIdentifier(databaseName)).append('.');
        }
        script.append(sqliteIdentifierProcessor.quoteIdentifier(tableName));
    }
}
