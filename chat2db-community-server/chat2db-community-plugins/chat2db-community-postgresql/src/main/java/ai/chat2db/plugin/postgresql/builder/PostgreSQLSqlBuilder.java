package ai.chat2db.plugin.postgresql.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.community.domain.api.enums.plugin.DmlTypeEnum;
import ai.chat2db.plugin.postgresql.PostgreSqlGuards;
import ai.chat2db.plugin.postgresql.enums.PostgreSQLViewCheckOptionEnum;
import ai.chat2db.plugin.postgresql.enums.type.PostgreSQLColumnTypeEnum;
import ai.chat2db.plugin.postgresql.enums.type.PostgreSQLIndexTypeEnum;
import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


import static ai.chat2db.plugin.postgresql.constant.PostgreSQLSqlBuilderConstants.*;
public class PostgreSQLSqlBuilder extends DefaultSqlBuilder {

    @Override
    public String quoteIdentifier(String identifier) {
        return quotePostgreSqlIdentifier(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        if (identifiers.length == 3) {
            return quoteQualifiedIdentifier(identifiers[1], identifiers[2]);
        }
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .map(PostgreSQLSqlBuilder::quotePostgreSqlIdentifier)
                .collect(Collectors.joining(SQLConstants.DOT));
    }

    @Override
    public String quoteAlias(String alias) {
        return quoteIdentifier(alias);
    }

    @Override
    public String buildUpdate(UpdateSqlRequest request) {
        StringBuilder script = new StringBuilder(SQLConstants.UPDATE_KEYWORD + SQLConstants.SPACE);
        buildTableName(request.getDatabaseName(), request.getSchemaName(), request.getTableName(), script);
        script.append(" SET ");
        script.append(request.getRow().entrySet().stream()
                .map(entry -> PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(entry.getKey())
                        + SQLConstants.EQUAL_SQL + entry.getValue())
                .collect(Collectors.joining(SQLConstants.COMMA)));
        if (MapUtils.isNotEmpty(request.getPrimaryKeyMap())) {
            script.append(" WHERE ");
            script.append(request.getPrimaryKeyMap().entrySet().stream()
                    .map(entry -> PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(entry.getKey())
                            + SQLConstants.EQUAL_SQL + entry.getValue())
                    .collect(Collectors.joining(SQLConstants.SQL_AND)));
        }
        return script.toString();
    }

    @Override
    public String buildTemplate(Table table, String type) {
        if (table == null || CollectionUtils.isEmpty(table.getColumnList()) || StringUtils.isBlank(type)) {
            return SQLConstants.EMPTY;
        }
        String tableName = quoteQualifiedIdentifier(table.getSchemaName(), table.getName());
        List<String> columnNames = table.getColumnList().stream()
                .map(column -> PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName()))
                .toList();
        if (DmlTypeEnum.INSERT.name().equalsIgnoreCase(type)) {
            return "INSERT INTO " + tableName + " (" + String.join(SQLConstants.COMMA, columnNames)
                    + ") VALUES (" + columnNames.stream().map(name -> SQLConstants.SPACE)
                    .collect(Collectors.joining(SQLConstants.COMMA)) + ")";
        }
        if (DmlTypeEnum.UPDATE.name().equalsIgnoreCase(type)) {
            return "UPDATE " + tableName + " SET " + columnNames.stream()
                    .map(name -> name + SQLConstants.EQUAL_SQL + SQLConstants.SPACE)
                    .collect(Collectors.joining(SQLConstants.COMMA)) + " WHERE ";
        }
        if (DmlTypeEnum.DELETE.name().equalsIgnoreCase(type)) {
            return "DELETE FROM " + tableName + " WHERE ";
        }
        if (DmlTypeEnum.SELECT.name().equalsIgnoreCase(type)) {
            return "SELECT " + String.join(SQLConstants.COMMA, columnNames) + " FROM " + tableName;
        }
        return SQLConstants.EMPTY;
    }



























    @Override
    protected String appendSingleRowLimit(String operationType, String tableName, String whereClause, String sql) {
        if (StringUtils.isBlank(whereClause) || !sql.endsWith(whereClause)) {
            return sql;
        }
        String body = sql.substring(0, sql.length() - whereClause.length());
        return body + SQL_WHERE_CTID_IN_OPEN_PAREN_SELECT_CTID_FROM + tableName + whereClause + VALUE_LIMIT_1_CLOSE_PAREN;
    }

    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        Boolean needFullTableName = tableBuilderConfig.getNeedFullTableName();
        StringBuilder script = new StringBuilder();
        script.append(SQL_CREATE_TABLE);
        if (needFullTableName) {
            script.append(quoteQualifiedIdentifier(table.getSchemaName(), table.getName()));
        } else {
            script.append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(table.getName()));
        }
        script.append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.SPACE).append(SQLConstants.LINE_SEPARATOR);
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            script.append(SQLConstants.TAB)
                    .append(PostgreSQLColumnTypeEnum.buildCreateColumnSqlSafely(column))
                    .append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        Map<Boolean, List<TableIndex>> tableIndexMap = table.getIndexList().stream()
                .collect(Collectors.partitioningBy(v -> PostgreSQLIndexTypeEnum.NORMAL.getName().equals(v.getType())));
        List<TableIndex> constraintList = tableIndexMap.get(Boolean.FALSE);
        if (CollectionUtils.isNotEmpty(constraintList)) {
            for (TableIndex index : constraintList) {
                if (StringUtils.isBlank(index.getName()) || StringUtils.isBlank(index.getType())) {
                    continue;
                }
                PostgreSQLIndexTypeEnum indexTypeEnum = PostgreSQLIndexTypeEnum.getByType(index.getType());
                if (indexTypeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(SQLConstants.EMPTY).append(indexTypeEnum.buildIndexScript(index));
                script.append(SQLConstants.COMMA_LINE_SEPARATOR);
            }

        }
        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS).append(SQLConstants.SEMICOLON);
        List<TableIndex> tableIndexList = tableIndexMap.get(Boolean.TRUE);
        for (TableIndex tableIndex : tableIndexList) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR);
            PostgreSQLIndexTypeEnum indexTypeEnum = PostgreSQLIndexTypeEnum.getByType(tableIndex.getType());
            if (indexTypeEnum == null) {
                continue;
            }
            script.append(SQLConstants.EMPTY).append(indexTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.SEMICOLON);
        }
        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQLConstants.LINE_SEPARATOR);
            script.append(SQL_COMMENT_TABLE).append(SQLConstants.SPACE)
                    .append(quoteQualifiedIdentifier(
                            BooleanUtils.isTrue(needFullTableName) ? table.getSchemaName() : null,
                            table.getName()))
                    .append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE)
                    .append(PostgreSQLIdentifierProcessor.INSTANCE.escapeString(table.getComment())).append(SQLConstants.SINGLE_QUOTE_SEMICOLON_LINE_SEPARATOR);
        }
        List<TableColumn> tableColumnList = table.getColumnList().stream().filter(v -> StringUtils.isNotBlank(v.getComment())).toList();
        for (TableColumn tableColumn : tableColumnList) {
            PostgreSQLColumnTypeEnum typeEnum = PostgreSQLColumnTypeEnum.getByType(tableColumn.getColumnType());
            if (typeEnum == null) {
                continue;
            }
            script.append(typeEnum.buildComment(tableColumn, typeEnum)).append(SQLConstants.LINE_SEPARATOR);
            ;
        }
        List<TableIndex> indexList = table.getIndexList().stream().filter(v -> StringUtils.isNotBlank(v.getComment())).toList();
        for (TableIndex index : indexList) {
            PostgreSQLIndexTypeEnum indexEnum = PostgreSQLIndexTypeEnum.getByType(index.getType());
            if (indexEnum == null) {
                continue;
            }
            script.append(indexEnum.buildIndexComment(index)).append(SQLConstants.LINE_SEPARATOR);
            ;
        }

        return script.toString();
    }

    @Override
    public String buildAITableSchema(Table table) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_CREATE_TABLE);
        script.append(quoteQualifiedIdentifier(table.getSchemaName(), table.getName()))
                .append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.SPACE)
                .append(SQLConstants.LINE_SEPARATOR);
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            script.append(SQLConstants.TAB)
                    .append(PostgreSQLColumnTypeEnum.buildAICreateColumnSqlSafely(column))
                    .append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        if (CollectionUtils.isEmpty(table.getIndexList())) {
            table.setIndexList(List.of());
        }
        Map<Boolean, List<TableIndex>> tableIndexMap = table.getIndexList().stream()
                .collect(Collectors.partitioningBy(v -> PostgreSQLIndexTypeEnum.NORMAL.getName().equals(v.getType())));
        List<TableIndex> constraintList = tableIndexMap.get(Boolean.FALSE);
        if (CollectionUtils.isNotEmpty(constraintList)) {
            for (TableIndex index : constraintList) {
                if (StringUtils.isBlank(index.getName()) || StringUtils.isBlank(index.getType())) {
                    continue;
                }
                PostgreSQLIndexTypeEnum indexTypeEnum = PostgreSQLIndexTypeEnum.getByType(index.getType());
                if (indexTypeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(SQLConstants.EMPTY).append(indexTypeEnum.buildIndexScript(index));
                script.append(SQLConstants.COMMA_LINE_SEPARATOR);
            }

        }
        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS).append(SQLConstants.SEMICOLON);
        List<TableIndex> tableIndexList = tableIndexMap.get(Boolean.TRUE);
        for (TableIndex tableIndex : tableIndexList) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR);
            PostgreSQLIndexTypeEnum indexTypeEnum = PostgreSQLIndexTypeEnum.getByType(tableIndex.getType());
            if (indexTypeEnum == null) {
                continue;
            }
            script.append(SQLConstants.EMPTY).append(indexTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.SEMICOLON);
        }
        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQLConstants.LINE_SEPARATOR);
            script.append(SQL_COMMENT_TABLE).append(SQLConstants.SPACE)
                    .append(quoteQualifiedIdentifier(table.getSchemaName(), table.getName()))
                    .append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE)
                    .append(PostgreSQLIdentifierProcessor.INSTANCE.escapeString(table.getComment())).append(SQLConstants.SINGLE_QUOTE_SEMICOLON_LINE_SEPARATOR);
        }
        List<TableIndex> indexList = table.getIndexList().stream().filter(v -> StringUtils.isNotBlank(v.getComment())).toList();
        for (TableIndex index : indexList) {
            PostgreSQLIndexTypeEnum indexEnum = PostgreSQLIndexTypeEnum.getByType(index.getType());
            if (indexEnum == null) {
                continue;
            }
            script.append(indexEnum.buildIndexComment(index)).append(SQLConstants.LINE_SEPARATOR);
        }

        return script.toString();
    }


    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();
        String oldQualifiedName = quoteQualifiedIdentifier(oldTable.getSchemaName(), oldTable.getName());
        String newQualifiedName = quoteQualifiedIdentifier(newTable.getSchemaName(), newTable.getName());
        if (!StringUtils.equals(oldTable.getName(), newTable.getName())) {
            script.append(SQL_ALTER_TABLE).append(oldQualifiedName);
            script.append(SQLConstants.TAB).append(SQL_RENAME).append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTable.getName())).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);

        }
        newTable.setIndexList(newTable.getIndexList().stream().filter(v -> StringUtils.isNotBlank(v.getEditStatus())).toList());
        List<TableColumn> columnNameList = newTable.getColumnList().stream().filter(v ->
                v.getOldName() != null && !StringUtils.equals(v.getOldName(), v.getName())).toList();
        for (TableColumn tableColumn : columnNameList) {
            script.append(SQL_ALTER_TABLE).append(newQualifiedName).append(VALUE_DOUBLE_QUOTE)
                    .append("RENAME COLUMN ")
                    .append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getOldName()))
                    .append(" TO ")
                    .append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getName()))
                    .append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }

        Map<Boolean, List<TableIndex>> tableIndexMap = newTable.getIndexList().stream()
                .collect(Collectors.partitioningBy(v -> PostgreSQLIndexTypeEnum.NORMAL.getName().equals(v.getType())));
        StringBuilder scriptModify = new StringBuilder();
        Boolean modify = false;
        scriptModify.append(SQL_ALTER_TABLE).append(newQualifiedName).append(VALUE_DOUBLE_QUOTE_2);
        List<TableColumn> columnList = newTable.getColumnList();
        for (TableColumn tableColumn : columnList) {
            String editStatus = tableColumn.getEditStatus();
            if (StringUtils.isBlank(editStatus)) {
                continue;
            }
            String modifyColumn = PostgreSQLColumnTypeEnum.buildModifyColumnSafely(tableColumn);
            if (StringUtils.isNotBlank(modifyColumn)) {
                scriptModify.append(SQLConstants.TAB).append(modifyColumn).append(SQLConstants.COMMA_LINE_SEPARATOR);
                modify = true;
            }

        }
        for (TableIndex tableIndex : tableIndexMap.get(Boolean.FALSE)) {
            if (StringUtils.isNotBlank(tableIndex.getType())) {
                PostgreSQLIndexTypeEnum indexTypeEnum = PostgreSQLIndexTypeEnum.getByType(tableIndex.getType());
                if (indexTypeEnum == null) {
                    continue;
                }
                scriptModify.append(SQLConstants.TAB).append(indexTypeEnum.buildModifyIndex(tableIndex)).append(SQLConstants.COMMA_LINE_SEPARATOR);
                modify = true;
            }
        }

        if (BooleanUtils.isTrue(modify)) {
            script.append(scriptModify);
            script = new StringBuilder(script.substring(0, script.length() - 2));
            script.append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        for (TableIndex tableIndex : tableIndexMap.get(Boolean.TRUE)) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                PostgreSQLIndexTypeEnum indexTypeEnum = PostgreSQLIndexTypeEnum.getByType(tableIndex.getType());
                if (indexTypeEnum == null) {
                    continue;
                }
                script.append(indexTypeEnum.buildModifyIndex(tableIndex)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            }
        }
        if (!StringUtils.equals(oldTable.getComment(), newTable.getComment())) {
            script.append(SQLConstants.LINE_SEPARATOR);
            script.append(SQL_COMMENT_TABLE).append(SQLConstants.SPACE).append(newQualifiedName).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE)
                    .append(PostgreSQLIdentifierProcessor.INSTANCE.escapeString(newTable.getComment())).append(SQLConstants.SINGLE_QUOTE_SEMICOLON_LINE_SEPARATOR);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            PostgreSQLColumnTypeEnum typeEnum = PostgreSQLColumnTypeEnum.getByType(tableColumn.getColumnType());
            if (typeEnum == null) {
                continue;
            }
            script.append(typeEnum.buildComment(tableColumn, typeEnum)).append(SQLConstants.LINE_SEPARATOR);
        }
        List<TableIndex> indexList = newTable.getIndexList().stream().filter(v -> StringUtils.isNotBlank(v.getComment())).toList();
        for (TableIndex index : indexList) {
            PostgreSQLIndexTypeEnum indexEnum = PostgreSQLIndexTypeEnum.getByType(index.getType());
            if (indexEnum == null) {
                continue;
            }
            script.append(indexEnum.buildIndexComment(index)).append(SQLConstants.LINE_SEPARATOR);
        }

        return script.toString();
    }

    @Override
    public String buildPageLimit(PageLimitRequest request) {
        String sql = request.getSql();
        int offset = request.getOffset();
        int pageNo = request.getPageNo();
        int pageSize = request.getPageSize();
        StringBuilder sqlStr = new StringBuilder(sql.length() + 17);
        sqlStr.append(sql);
        if (offset == 0) {
            sqlStr.append(SQL_LIMIT);
            sqlStr.append(pageSize);
        } else {
            sqlStr.append(SQL_LIMIT);
            sqlStr.append(pageSize);
            sqlStr.append(SQL_OFFSET);
            sqlStr.append(offset);
        }
        return sqlStr.toString();
    }

    @Override
    public String buildCreateDatabase(Database database) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQL_CREATE_DATABASE).append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database.getName()));
        sqlBuilder.append(SQLConstants.LINE_SEPARATOR_SQL_WITH);
        if (StringUtils.isNotBlank(database.getCharset())) {
            sqlBuilder.append(VALUE_LC_CTYPE_EQUAL_SINGLE_QUOTE).append(PostgreSQLIdentifierProcessor.INSTANCE.escapeString(database.getCharset())).append(VALUE_SINGLE_QUOTE);
        }
        if (StringUtils.isNotBlank(database.getCollation())) {
            sqlBuilder.append(SQL_LC_COLLATE_EQUAL_SINGLE_QUOTE).append(PostgreSQLIdentifierProcessor.INSTANCE.escapeString(database.getCollation())).append(VALUE_SINGLE_QUOTE);
        }

        if (StringUtils.isNotBlank(database.getComment())) {
            sqlBuilder.append(SQL_SEMICOLON_COMMENT_ON_DATABASE_DOUBLE_QUOTE).append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database.getName())).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(PostgreSQLIdentifierProcessor.INSTANCE.escapeString(database.getComment())).append(SQLConstants.SINGLE_QUOTE_SEMICOLON);
        }
        return sqlBuilder.toString();
    }

    @Override
    public String buildDropDatabase(String databaseName) {
        return String.format(DROP_DATABASE_SQL, quoteIdentifier(databaseName));
    }


    @Override
    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        script.append(quoteQualifiedIdentifier(databaseName, schemaName, tableName));
    }

    @Override
    protected void buildColumns(List<String> columnList, StringBuilder script) {
        if (CollectionUtils.isNotEmpty(columnList)) {
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS)
                    .append(columnList.stream()
                            .map(PostgreSQLIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                            .collect(Collectors.joining(SQLConstants.COMMA)))
                    .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
        }
    }

    @Override
    public String buildCreateSchema(Schema schema) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQL_CREATE_SCHEMA).append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schema.getName()));
        if (StringUtils.isNotBlank(schema.getOwner())) {
            sqlBuilder.append(SQLConstants.SCHEMA_AUTHORIZATION_SQL)
                    .append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schema.getOwner()));
        }
        if (StringUtils.isNotBlank(schema.getComment())) {
            sqlBuilder.append(SQL_SEMICOLON_COMMENT_ON_SCHEMA_DOUBLE_QUOTE).append(PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schema.getName())).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(PostgreSQLIdentifierProcessor.INSTANCE.escapeString(schema.getComment())).append(SQLConstants.SINGLE_QUOTE_SEMICOLON);
        }
        return sqlBuilder.toString();
    }

    @Override
    public String buildDropSchema(String schemaName) {
        return String.format(DROP_SCHEMA_SQL, quoteIdentifier(schemaName));
    }

    private static String quotePostgreSqlIdentifier(String name) {
        return PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(name);
    }

    private static String quotePostgreSqlStringLiteral(String value) {
        return SQLConstants.SINGLE_QUOTE
                + PostgreSQLIdentifierProcessor.INSTANCE.escapeString(value)
                + SQLConstants.SINGLE_QUOTE;
    }


    @Override
    public String buildCreateView(ModifyView modifyView) {
        StringBuilder createViewSqlBuilder = new StringBuilder(100);
        createViewSqlBuilder.append(SQL_CREATE);
        if (modifyView.isUseOrReplace()) {
            createViewSqlBuilder.append(SQL_REPLACE);
        }

        String tempClause = modifyView.getStorageClause();
        if (StringUtils.isNotBlank(tempClause)) {
            createViewSqlBuilder.append(PostgreSqlGuards.requireViewStorageClause(tempClause))
                    .append(SQLConstants.SPACE);
        }

        if (modifyView.isUseRecursive()) {
            createViewSqlBuilder.append(SQL_RECURSIVE);
        }
        createViewSqlBuilder.append(SQLConstants.VIEW_KEYWORD);
        String schemaName = modifyView.getSchemaName();
        String viewName = modifyView.getViewName();
        String qualifiedViewName;
        if (StringUtils.isNotBlank(viewName)) {
            qualifiedViewName = quoteQualifiedIdentifier(schemaName, viewName);
        } else {
            qualifiedViewName = UNDEFINED_KEYWORD;
        }
        createViewSqlBuilder.append(qualifiedViewName);
        createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR_SQL_AS);
        String viewBody = modifyView.getViewBody();
        createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR).append(viewBody).append(SQLConstants.SPACE);
        String checkOption = modifyView.getCheckOption();
        if (StringUtils.isNotBlank(checkOption)) {
            checkOption = PostgreSqlGuards.requireEnumConstant(checkOption, PostgreSQLViewCheckOptionEnum.values(), "view check option");
            createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR_SQL_WITH).append(checkOption).append(SQLConstants.CHECK_OPTION_SQL);
        }

        createViewSqlBuilder.append(SQLConstants.SEMICOLON);

        String comment = modifyView.getComment();
        if (StringUtils.isNotBlank(comment)) {
            createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR);
            createViewSqlBuilder.append(SQL_COMMENT_VIEW)
                    .append(SQLConstants.SPACE)
                    .append(qualifiedViewName)
                    .append(SQLConstants.SQL_IS_LOWER)
                    .append(quotePostgreSqlStringLiteral(comment))
                    .append(SQLConstants.SEMICOLON);
        }
        return createViewSqlBuilder.toString();
    }

}
