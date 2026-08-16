package ai.chat2db.plugin.db2.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.db2.identifier.Db2IdentifierProcessor;
import ai.chat2db.plugin.db2.enums.type.DB2ColumnTypeEnum;
import ai.chat2db.plugin.db2.enums.type.DB2IndexTypeEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import org.apache.commons.lang3.StringUtils;

import static ai.chat2db.plugin.db2.constant.DB2SqlBuilderConstants.*;
public class DB2SqlBuilder extends DefaultSqlBuilder {














    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();

        script.append(SQL_CREATE_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(table.getSchemaName())).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(table.getName())).append(VALUE_DOUBLE_QUOTE_OPEN_PAREN).append(SQLConstants.LINE_SEPARATOR);

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            DB2ColumnTypeEnum typeEnum = DB2ColumnTypeEnum.getByType(column.getColumnType());
            if (typeEnum == null) {
                continue;
            }
            script.append(SQLConstants.TAB).append(typeEnum.buildCreateColumnSql(column)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS_SEMICOLON);

        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            DB2IndexTypeEnum indexTypeEnum = DB2IndexTypeEnum.getByType(tableIndex.getType());
            if (indexTypeEnum == null) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR).append(SQLConstants.EMPTY).append(indexTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.SEMICOLON);
            if(StringUtils.isNotBlank(tableIndex.getComment())){
                script.append(SQLConstants.LINE_SEPARATOR).append(indexTypeEnum.buildIndexComment(tableIndex)).append(SQLConstants.SEMICOLON);
            }

        }

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType()) || StringUtils.isBlank(column.getComment())) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR).append(buildComment(column)).append(SQLConstants.SEMICOLON);
        }

        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQLConstants.LINE_SEPARATOR).append(buildTableComment(table)).append(SQLConstants.SEMICOLON);
        }


        return script.toString();
    }

    private String buildTableComment(Table table) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_COMMENT_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(table.getSchemaName())).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(table.getName())).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(Db2IdentifierProcessor.INSTANCE.escapeString(table.getComment())).append(SQLConstants.SINGLE_QUOTE);
        return script.toString();
    }

    private String buildComment(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_COMMENT_COLUMN).append(SQLConstants.DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(column.getSchemaName())).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(column.getTableName())).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(column.getName())).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(Db2IdentifierProcessor.INSTANCE.escapeString(column.getComment())).append(SQLConstants.SINGLE_QUOTE);
        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();

        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQL_ALTER_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(oldTable.getSchemaName())).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(oldTable.getName())).append(SQLConstants.DOUBLE_QUOTE);
            script.append(SQLConstants.SPACE).append(SQL_RENAME).append(SQLConstants.DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(newTable.getName())).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append(SQLConstants.EMPTY).append(buildTableComment(newTable)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus())) {
                DB2ColumnTypeEnum typeEnum = DB2ColumnTypeEnum.getByType(tableColumn.getColumnType());
                if (typeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(typeEnum.buildModifyColumn(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                if (StringUtils.isNotBlank(tableColumn.getComment())
                        && !EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
                    script.append(SQLConstants.LINE_SEPARATOR).append(buildComment(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                }
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                DB2IndexTypeEnum mysqlIndexTypeEnum = DB2IndexTypeEnum.getByType(tableIndex.getType());
                if (mysqlIndexTypeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(mysqlIndexTypeEnum.buildModifyIndex(tableIndex)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                if(StringUtils.isNotBlank(tableIndex.getComment())) {
                    script.append(SQLConstants.LINE_SEPARATOR).append(mysqlIndexTypeEnum.buildIndexComment(tableIndex)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                }

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
        int startRow = offset + 1;
        int endRow = offset + pageSize;
        StringBuilder sqlBuilder = new StringBuilder(sql.length() + 120);
        sqlBuilder.append(SQL_SELECT_SELECT_TMP_PAGE_ROWNUMBER);
        sqlBuilder.append(sql);
        sqlBuilder.append(SQL_CLOSE_PAREN_AS_TMP_PAGE_CLOSE_PAREN_TMP_PAGE_WHERE_CAHT2DB_AUTO_ROW_ID);
        sqlBuilder.append(startRow);
        sqlBuilder.append(SQL_AND);
        sqlBuilder.append(endRow);
        return sqlBuilder.toString();
    }

    @Override
    public String buildCreateSchema(Schema schema) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQL_CREATE_SCHEMA + Db2IdentifierProcessor.escapeIdentifier(schema.getName()) + SQLConstants.DOUBLE_QUOTE_SEMICOLON);

        if (StringUtils.isNotBlank(schema.getComment())) {
            sqlBuilder.append(SQL_COMMENT_ON_SCHEMA_DOUBLE_QUOTE).append(Db2IdentifierProcessor.escapeIdentifier(schema.getName())).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(Db2IdentifierProcessor.INSTANCE.escapeString(schema.getComment())).append(SQLConstants.SINGLE_QUOTE_SEMICOLON);
        }

        return sqlBuilder.toString();
    }

    @Override
    public String buildExplain(String sql) {
        return SQL_EXPLAIN_PLAN_SET_QUERYNO_EQUAL_1_FOR + sql;
    }

    @Override
    public String buildDropTable(ai.chat2db.spi.model.request.DropTableRequest request) {
        return SQLConstants.DROP_TABLE_SQL_PREFIX + quoteQualifiedName(request.getDatabaseName(), request.getSchemaName(), request.getTableName());
    }

    @Override
    public String buildTruncateTable(ai.chat2db.spi.model.request.TruncateTableRequest request) {
        return SQLConstants.TRUNCATE_TABLE_SQL_PREFIX + quoteQualifiedName(request.getDatabaseName(), request.getSchemaName(), request.getTableName());
    }

    private static String quoteQualifiedName(String... parts) {
        return java.util.Arrays.stream(parts)
                .filter(StringUtils::isNotBlank)
                .map(Db2IdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                .collect(java.util.stream.Collectors.joining(SQLConstants.DOT));
    }
}
