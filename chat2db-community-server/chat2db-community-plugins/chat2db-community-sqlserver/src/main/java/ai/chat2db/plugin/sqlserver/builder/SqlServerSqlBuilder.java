package ai.chat2db.plugin.sqlserver.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.sqlserver.enums.SqlServerViewAttributeOptionEnum;
import ai.chat2db.plugin.sqlserver.enums.type.SqlServerColumnTypeEnum;
import ai.chat2db.plugin.sqlserver.enums.type.SqlServerIndexTypeEnum;
import ai.chat2db.plugin.sqlserver.SqlServerSqlGuards;
import ai.chat2db.plugin.sqlserver.identifier.SqlServerIdentifierProcessor;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import ai.chat2db.community.domain.api.enums.plugin.DmlTypeEnum;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.model.async.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.sqlserver.constant.SqlServerSqlBuilderConstants.*;
public class SqlServerSqlBuilder extends DefaultSqlBuilder {

    @Override
    public String quoteIdentifier(String identifier) {
        return SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .map(SqlServerIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                .collect(Collectors.joining(SQLConstants.DOT));
    }

    @Override
    protected boolean useLikeForCopyWhere() {
        return false;
    }

    @Override
    protected String copyWhereColumnExpression(String columnName, String dataTypeName) {
        if (SqlServerColumnTypeEnum.TEXT.name().equalsIgnoreCase(dataTypeName)) {
            return "CAST(" + columnName + " AS VARCHAR(MAX))";
        }
        if (SqlServerColumnTypeEnum.NTEXT.name().equalsIgnoreCase(dataTypeName)) {
            return "CAST(" + columnName + " AS NVARCHAR(MAX))";
        }
        return columnName;
    }




























    @Override
    protected String appendSingleRowLimit(String operationType, String tableName, String whereClause, String sql) {
        if (SQLConstants.DELETE_KEYWORD.equalsIgnoreCase(operationType) && sql.regionMatches(true, 0, SQL_DELETE, 0, 7)) {
            return SQL_DELETE_TOP_OPEN_PAREN_1_CLOSE_PAREN + sql.substring(SQL_DELETE.length());
        }
        if (SQLConstants.UPDATE_KEYWORD.equalsIgnoreCase(operationType) && sql.regionMatches(true, 0, SQLConstants.UPDATE_SQL_PREFIX, 0, 7)) {
            return SQL_UPDATE_TOP_OPEN_PAREN_1_CLOSE_PAREN + sql.substring(SQLConstants.UPDATE_SQL_PREFIX.length());
        }
        return sql;
    }

    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();

        script.append(SQL_CREATE_TABLE);
        Boolean needFullTableName = tableBuilderConfig.getNeedFullTableName();
        script.append(Boolean.TRUE.equals(needFullTableName)
                        ? quoteQualifiedIdentifier(table.getDatabaseName(), table.getSchemaName(), table.getName())
                        : quoteQualifiedIdentifier(table.getSchemaName(), table.getName()))
                .append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            SqlServerColumnTypeEnum typeEnum = SqlServerColumnTypeEnum.getByType(column.getColumnType());
            if (typeEnum == null) {
                continue;
            }
            script.append(SQLConstants.TAB).append(typeEnum.buildCreateColumnSql(column)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(VALUE_CLOSE_PAREN_GO);

        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            SqlServerIndexTypeEnum sqlServerIndexTypeEnum = SqlServerIndexTypeEnum.getByType(tableIndex.getType());
            if (sqlServerIndexTypeEnum == null) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR).append(sqlServerIndexTypeEnum.buildIndexScript(tableIndex));
            if (StringUtils.isNotBlank(tableIndex.getComment())) {
                script.append(SQLConstants.LINE_SEPARATOR).append(buildIndexComment(tableIndex));
            }
        }

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType()) || StringUtils.isBlank(column.getComment())) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR).append(buildColumnComment(column));
        }

        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQLConstants.LINE_SEPARATOR).append(buildTableComment(table));
        }


        return script.toString();
    }

    @Override
    public String buildAITableSchema(Table table) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_CREATE_TABLE);
        script.append(quoteQualifiedIdentifier(table.getDatabaseName(), table.getSchemaName(), table.getName()))
                .append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            SqlServerColumnTypeEnum typeEnum = SqlServerColumnTypeEnum.getByType(column.getColumnType());
            if (typeEnum == null) {
                continue;
            }
            script.append(SQLConstants.TAB).append(typeEnum.buildAICreateColumnSql(column)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(VALUE_CLOSE_PAREN_GO);

        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQLConstants.LINE_SEPARATOR).append(buildTableComment(table));
        }
        if (CollectionUtils.isEmpty(table.getIndexList())) {
            table.setIndexList(List.of());
        }
        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            SqlServerIndexTypeEnum sqlServerIndexTypeEnum = SqlServerIndexTypeEnum.getByType(tableIndex.getType());
            if (sqlServerIndexTypeEnum == null) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR).append(sqlServerIndexTypeEnum.buildIndexScript(tableIndex));
            if (StringUtils.isNotBlank(tableIndex.getComment())) {
                script.append(SQLConstants.LINE_SEPARATOR).append(buildIndexComment(tableIndex));
            }
        }

        return script.toString();
    }




    private String buildIndexComment(TableIndex tableIndex) {
        return String.format(INDEX_COMMENT_SCRIPT, SqlServerIdentifierProcessor.INSTANCE.escapeString(tableIndex.getComment()), SqlServerIdentifierProcessor.INSTANCE.escapeString(tableIndex.getSchemaName()), SqlServerIdentifierProcessor.INSTANCE.escapeString(tableIndex.getTableName()), SqlServerIdentifierProcessor.INSTANCE.escapeString(tableIndex.getName()));
    }




    private String buildTableComment(Table table) {
        return String.format(TABLE_COMMENT_SCRIPT, SqlServerIdentifierProcessor.INSTANCE.escapeString(table.getComment()), SqlServerIdentifierProcessor.INSTANCE.escapeString(table.getSchemaName()), SqlServerIdentifierProcessor.INSTANCE.escapeString(table.getName()));
    }



    private String buildColumnComment(TableColumn column) {
        return String.format(COLUMN_COMMENT_SCRIPT, SqlServerIdentifierProcessor.INSTANCE.escapeString(column.getComment()), SqlServerIdentifierProcessor.INSTANCE.escapeString(column.getSchemaName()), SqlServerIdentifierProcessor.INSTANCE.escapeString(column.getTableName()), SqlServerIdentifierProcessor.INSTANCE.escapeString(column.getName()));
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();

        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(buildRenameTable(oldTable, newTable));
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            if (oldTable.getComment() == null) {
                script.append(SQLConstants.LINE_SEPARATOR).append(buildTableComment(newTable));
            } else {
                script.append(SQLConstants.LINE_SEPARATOR).append(buildUpdateTableComment(newTable));
            }
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus())) {
                SqlServerColumnTypeEnum typeEnum = SqlServerColumnTypeEnum.getByType(tableColumn.getColumnType());
                if (typeEnum == null) {
                    continue;
                }
                script.append(typeEnum.buildModifyColumn(tableColumn)).append(SQLConstants.LINE_SEPARATOR);
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                SqlServerIndexTypeEnum mysqlIndexTypeEnum = SqlServerIndexTypeEnum.getByType(tableIndex.getType());
                if (mysqlIndexTypeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(mysqlIndexTypeEnum.buildModifyIndex(tableIndex)).append(SQLConstants.LINE_SEPARATOR);
                if (StringUtils.isNotBlank(tableIndex.getComment())) {
                    script.append(SQLConstants.LINE_SEPARATOR).append(buildIndexComment(tableIndex)).append(VALUE_GO);
                }
            }
        }

        return script.toString();
    }



    private String buildUpdateTableComment(Table newTable) {
        return String.format(UPDATE_TABLE_COMMENT_SCRIPT, SqlServerIdentifierProcessor.INSTANCE.escapeString(newTable.getComment()), SqlServerIdentifierProcessor.INSTANCE.escapeString(newTable.getSchemaName()), SqlServerIdentifierProcessor.INSTANCE.escapeString(newTable.getName()));
    }



    private String buildRenameTable(Table oldTable, Table newTable) {
        return String.format(RENAME_TABLE_SCRIPT,
                SqlServerIdentifierProcessor.INSTANCE.escapeString(
                        quoteQualifiedIdentifier(oldTable.getSchemaName(), oldTable.getName())),
                SqlServerIdentifierProcessor.INSTANCE.escapeString(newTable.getName()));
    }

    @Override
    public String buildPageLimit(PageLimitRequest request) {
        String sql = request.getSql();
        int offset = request.getOffset();
        int pageNo = request.getPageNo();
        int pageSize = request.getPageSize();
        String version = Chat2DBContext.getDbVersion();
        if (StringUtils.isNotBlank(version)) {
            String[] versions = version.split(VALUE_BACKSLASH_DOT);
            if (versions.length > 0 && Integer.parseInt(versions[0]) >= 11) {
                StringBuilder sqlBuilder = new StringBuilder(sql.length() + 14);
                sqlBuilder.append(sql);
                if (!sql.toLowerCase(Locale.ROOT).contains(ORDER_BY_KEYWORD_LOWER)) {
                    sqlBuilder.append(SQL_ORDER_BY_OPEN_PAREN_SELECT_NULL_CLOSE_PAREN);
                }
                sqlBuilder.append(SQLConstants.LINE_SEPARATOR_OFFSET_SQL);
                sqlBuilder.append(offset);
                sqlBuilder.append(SQLConstants.ROWS_SQL);
                sqlBuilder.append(SQLConstants.FETCH_NEXT_SQL);
                sqlBuilder.append(pageSize);
                sqlBuilder.append(SQLConstants.ROWS_ONLY_SQL);
                return sqlBuilder.toString();
            }
        }
        // SQL Server < 2012: use ROW_NUMBER() window function
        int startRow = offset + 1;
        int endRow = offset + pageSize;
        StringBuilder sqlBuilder = new StringBuilder(sql.length() + 120);
        sqlBuilder.append(SQL_ROW_NUMBER_PREFIX);
        sqlBuilder.append(sql);
        sqlBuilder.append(SQL_ROW_NUMBER_SUFFIX);
        sqlBuilder.append(startRow);
        sqlBuilder.append(SQL_AND);
        sqlBuilder.append(endRow);
        return sqlBuilder.toString();
    }


    @Override
    public String buildCreateDatabase(Database database) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("CREATE DATABASE ").append(quoteIdentifier(database.getName()));
        if (StringUtils.isNotBlank(database.getCollation())) {
            sqlBuilder.append(SQL_COLLATE).append(SqlServerSqlGuards.validateCollation(database.getCollation()));
        }
        sqlBuilder.append(VALUE_GO);
        if (StringUtils.isNotBlank(database.getComment())) {
            sqlBuilder.append("exec ").append(quoteIdentifier(database.getName()))
                    .append(".sys.sp_addextendedproperty 'MS_Description','")
                    .append(SqlServerIdentifierProcessor.INSTANCE.escapeString(database.getComment())).append(SQLConstants.SINGLE_QUOTE).append(VALUE_GO);
        }
        return sqlBuilder.toString();
    }

    @Override
    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        String normalizedTableName = SqlServerIdentifierProcessor.INSTANCE.isQuoteIdentifier(tableName)
                ? SqlServerIdentifierProcessor.INSTANCE.removeIdentifierQuote(tableName)
                : tableName;
        script.append(quoteQualifiedIdentifier(databaseName, schemaName, normalizedTableName));
    }


    @Override
    protected void buildColumns(List<String> columnList, StringBuilder script) {
        if (CollectionUtils.isNotEmpty(columnList)) {
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS)
                    .append(columnList.stream().map(SqlServerIdentifierProcessor.INSTANCE::quoteIdentifierAlways).collect(Collectors.joining(SQLConstants.COMMA)))
                    .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
        }
    }

    @Override
    public String buildCreateSchema(Schema schema) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("CREATE SCHEMA ").append(quoteIdentifier(schema.getName())).append(VALUE_GO);
        if (StringUtils.isNotBlank(schema.getComment())) {
            sqlBuilder.append(SQL_EXEC_SP_ADDEXTENDEDPROPERTY_SINGLE_QUOTE_MS_DESCRIPTION_SINGLE_QUOTE_COMMA_SINGLE_QUOTE)
                    .append(SqlServerIdentifierProcessor.INSTANCE.escapeString(schema.getComment())).append(SQLConstants.SINGLE_QUOTE).append(SQL_COMMA_SINGLE_QUOTE_SCHEMA_SINGLE_QUOTE)
                    .append(VALUE_COMMA_SINGLE_QUOTE).append(SqlServerIdentifierProcessor.INSTANCE.escapeString(schema.getName())).append(SQLConstants.SINGLE_QUOTE).append(VALUE_GO);
        }
        return sqlBuilder.toString();
    }

    @Override
    public String buildUpdate(UpdateSqlRequest request) {
        StringBuilder script = new StringBuilder(SQLConstants.UPDATE_SQL_PREFIX);
        buildTableName(request.getDatabaseName(), request.getSchemaName(), request.getTableName(), script);
        script.append(SQLConstants.SET_SQL);
        List<String> setClauses = request.getRow().entrySet().stream()
                .map(entry -> quoteIdentifier(entry.getKey()) + SQLConstants.EQUAL_SQL + entry.getValue())
                .collect(Collectors.toList());
        script.append(String.join(SQLConstants.COMMA, setClauses));
        if (MapUtils.isNotEmpty(request.getPrimaryKeyMap())) {
            script.append(SQLConstants.WHERE_SQL);
            List<String> whereClauses = request.getPrimaryKeyMap().entrySet().stream()
                    .map(entry -> quoteIdentifier(entry.getKey()) + SQLConstants.EQUAL_SQL + entry.getValue())
                    .collect(Collectors.toList());
            script.append(String.join(SQLConstants.SQL_AND, whereClauses));
        }
        return script.toString();
    }

    @Override
    public String buildTemplate(Table table, String type) {
        if (table == null || CollectionUtils.isEmpty(table.getColumnList()) || StringUtils.isBlank(type)) {
            return SQLConstants.EMPTY;
        }
        String tableName = quoteQualifiedIdentifier(
                table.getDatabaseName(), table.getSchemaName(), table.getName());
        List<String> columnNames = table.getColumnList().stream()
                .map(column -> quoteIdentifier(column.getName()))
                .collect(Collectors.toList());
        if (DmlTypeEnum.INSERT.name().equalsIgnoreCase(type)) {
            return SQLConstants.INSERT_INTO_SQL_PREFIX + tableName + SQLConstants.SPACE_OPEN_PARENTHESIS
                    + String.join(SQLConstants.COMMA, columnNames)
                    + SQLConstants.CLOSE_PARENTHESIS + SQLConstants.VALUES_SQL + SQLConstants.OPEN_PARENTHESIS
                    + columnNames.stream().map(name -> SQLConstants.SPACE)
                    .collect(Collectors.joining(SQLConstants.COMMA))
                    + SQLConstants.CLOSE_PARENTHESIS;
        }
        if (DmlTypeEnum.UPDATE.name().equalsIgnoreCase(type)) {
            String setClause = columnNames.stream()
                    .map(name -> name + SQLConstants.EQUAL_SQL + SQLConstants.SPACE)
                    .collect(Collectors.joining(SQLConstants.COMMA));
            return SQLConstants.UPDATE_SQL_PREFIX + tableName + SQLConstants.SET_SQL + setClause;
        }
        if (DmlTypeEnum.DELETE.name().equalsIgnoreCase(type)) {
            return SQLConstants.DELETE_FROM_SQL_PREFIX + tableName;
        }
        return SQLConstants.EMPTY;
    }

    @Override
    public String buildCreateView(ModifyView modifyView) {
        StringBuilder createViewSqlBuilder = new StringBuilder(100);
        createViewSqlBuilder.append(SQL_CREATE);
        if (modifyView.isUseOrAlter()) {
            createViewSqlBuilder.append(SQL_ALTER);
        }
        createViewSqlBuilder.append(SQLConstants.VIEW_KEYWORD);

        List<String> viewAttributes = modifyView.getViewAttributes();
        if (CollectionUtils.isNotEmpty(viewAttributes)) {
            for (String viewAttribute : viewAttributes) {
                validateViewAttribute(viewAttribute);
            }
            createViewSqlBuilder.append(SQLConstants.SQL_WITH).append(String.join(SQLConstants.COMMA, viewAttributes)).append(SQLConstants.SPACE);
        }
        String schemaName = modifyView.getSchemaName();
        if (StringUtils.isNotBlank(schemaName)) {
            createViewSqlBuilder.append(SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName)).append(SQLConstants.DOT);
        }
        String viewName = modifyView.getViewName();
        if (StringUtils.isNotBlank(viewName)) {
            createViewSqlBuilder.append(SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(viewName));
        } else {
            createViewSqlBuilder.append(UNDEFINED_KEYWORD);
        }
        createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR_SQL_AS);
        String viewBody = modifyView.getViewBody();
        createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR).append(viewBody).append(SQLConstants.SPACE);
        String checkOption = modifyView.getCheckOption();
        if (StringUtils.isNotBlank(checkOption)) {
            createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR_WITH_CHECK_OPTION);
        }
        createViewSqlBuilder.append(SQLConstants.SEMICOLON);
        String comment = modifyView.getComment();
        if (StringUtils.isNotBlank(comment) && StringUtils.isNotBlank(schemaName)) {
            createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR);
            createViewSqlBuilder.append(SQL_EXEC_SP_ADDEXTENDEDPROPERTY_SINGLE_QUOTE_MS_DESCRIPTION_SINGLE_QUOTE_COMMA)
                    .append(SQLConstants.SPACE)
                    .append(SQLConstants.SINGLE_QUOTE).append(SqlServerIdentifierProcessor.INSTANCE.escapeString(comment)).append(SQLConstants.SINGLE_QUOTE).append(SQLConstants.COMMA)
                    .append(SQLConstants.SPACE)
                    .append(SQL_SINGLE_QUOTE_SCHEMA_SINGLE_QUOTE).append(SQLConstants.COMMA)
                    .append(SQLConstants.SPACE)
                    .append(SQLConstants.SINGLE_QUOTE).append(SqlServerIdentifierProcessor.INSTANCE.escapeString(schemaName)).append(SQLConstants.SINGLE_QUOTE).append(SQLConstants.COMMA)
                    .append(SQLConstants.SPACE)
                    .append(SQL_SINGLE_QUOTE_VIEW_SINGLE_QUOTE).append(SQLConstants.COMMA)
                    .append(SQLConstants.SPACE)
                    .append(SQLConstants.SINGLE_QUOTE).append(SqlServerIdentifierProcessor.INSTANCE.escapeString(viewName)).append(SQLConstants.SINGLE_QUOTE);

        }

        return createViewSqlBuilder.toString();
    }

    @Override
    public String buildExplain(String sql) {
        return SQL_SET_SHOWPLAN_XML_ON_SEMICOLON_GO + sql + SQL_GO_SET_SHOWPLAN_XML_OFF_SEMICOLON;
    }

    private static void validateViewAttribute(String viewAttribute) {
        for (SqlServerViewAttributeOptionEnum option : SqlServerViewAttributeOptionEnum.values()) {
            if (option.name().equalsIgnoreCase(viewAttribute)) {
                return;
            }
        }
        throw new IllegalArgumentException("Invalid SQL Server view attribute: " + viewAttribute);
    }
}
