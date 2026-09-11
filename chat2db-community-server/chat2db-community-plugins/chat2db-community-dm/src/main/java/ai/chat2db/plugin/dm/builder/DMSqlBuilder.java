package ai.chat2db.plugin.dm.builder;

import ai.chat2db.plugin.dm.parser.DMExecutableSql;
import ai.chat2db.plugin.dm.parser.DMSqlParser;
import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.dm.identifier.DMIdentifierProcessor;
import ai.chat2db.plugin.dm.enums.type.DMColumnTypeEnum;
import ai.chat2db.plugin.dm.enums.type.DMIndexTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.DmlTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
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
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.dm.constant.DMSqlBuilderConstants.*;
public class DMSqlBuilder  extends DefaultSqlBuilder {

    private final DMSqlParser parser = new DMSqlParser();

    @Override
    public String quoteIdentifier(String identifier) {
        return DMIdentifierProcessor.INSTANCE.quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        if (identifiers.length == 3) {
            String qualifier = StringUtils.isNotBlank(identifiers[1]) ? identifiers[1] : identifiers[0];
            return quoteQualifiedIdentifier(qualifier, identifiers[2]);
        }
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .map(DMIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                .collect(Collectors.joining(SQLConstants.DOT));
    }

    @Override
    public String buildTemplate(Table table, String type) {
        if (table == null || CollectionUtils.isEmpty(table.getColumnList()) || StringUtils.isBlank(type)) {
            return SQLConstants.EMPTY;
        }
        String tableName = quoteQualifiedIdentifier(table.getSchemaName(), table.getName());
        List<String> columnNames = table.getColumnList().stream()
                .map(column -> quoteIdentifier(column.getName()))
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
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();

        script.append(SQL_CREATE_TABLE)
                .append(quoteQualifiedIdentifier(table.getDatabaseName(), table.getSchemaName(), table.getName()))
                .append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            DMColumnTypeEnum typeEnum = DMColumnTypeEnum.getByType(column.getColumnType());
            typeEnum = typeEnum == null ? DMColumnTypeEnum.VARCHAR : typeEnum;
            script.append(SQLConstants.TAB).append(typeEnum.buildCreateColumnSql(column)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS_SEMICOLON);

        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            DMIndexTypeEnum indexTypeEnum = DMIndexTypeEnum.getByType(tableIndex.getType());
            if(indexTypeEnum == null){
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR).append(SQLConstants.EMPTY).append(indexTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.SEMICOLON);
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

    @Override
    public String buildAITableSchema(Table table) {
        List<TableColumn> columnList = table.getColumnList();
        if (CollectionUtils.isEmpty(columnList)) {
            table.setColumnList(List.of());
        }
        List<TableIndex> indexList = table.getIndexList();
        if (CollectionUtils.isEmpty(indexList)) {
            table.setIndexList(List.of());
        }
        List<ForeignKeyInfo> foreignKeyList = table.getForeignKeyList();
        if (CollectionUtils.isEmpty(foreignKeyList)) {
            table.setForeignKeyList(List.of());
        }
        StringBuilder script = new StringBuilder();

        script.append(SQL_CREATE_TABLE)
                .append(quoteQualifiedIdentifier(table.getDatabaseName(), table.getSchemaName(), table.getName()))
                .append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            DMColumnTypeEnum typeEnum = DMColumnTypeEnum.getByType(column.getColumnType());
            typeEnum = typeEnum == null ? DMColumnTypeEnum.VARCHAR : typeEnum;
            script.append(SQLConstants.TAB).append(typeEnum.buildAICreateColumnSql(column)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS_SEMICOLON);

        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            DMIndexTypeEnum indexTypeEnum = DMIndexTypeEnum.getByType(tableIndex.getType());
            if(indexTypeEnum == null){
                continue;
            }
            script.append(SQLConstants.LINE_SEPARATOR).append(indexTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.SEMICOLON);
        }


        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQLConstants.LINE_SEPARATOR).append(buildTableComment(table)).append(SQLConstants.SEMICOLON);
        }


        return script.toString();
    }

    private String buildTableComment(Table table) {
        return SQL_COMMENT_TABLE
                + quoteQualifiedIdentifier(table.getDatabaseName(), table.getSchemaName(), table.getName())
                + " IS " + quoteStringLiteral(table.getComment());
    }

    private String buildComment(TableColumn column) {
        return SQL_COMMENT_COLUMN
                + quoteQualifiedIdentifier(column.getSchemaName(), column.getTableName())
                + SQLConstants.DOT + quoteIdentifier(column.getName())
                + " IS " + quoteStringLiteral(column.getComment());
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();

        if (!StringUtils.equals(oldTable.getName(), newTable.getName())) {
            script.append(SQL_ALTER_TABLE)
                    .append(quoteQualifiedIdentifier(oldTable.getDatabaseName(), oldTable.getSchemaName(), oldTable.getName()));
            script.append(SQLConstants.SPACE).append(SQL_RENAME).append(quoteIdentifier(newTable.getName()))
                    .append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append(SQLConstants.EMPTY).append(buildTableComment(newTable)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            String editStatus = tableColumn.getEditStatus();
            if (StringUtils.isNotBlank(editStatus)) {
                DMColumnTypeEnum typeEnum = DMColumnTypeEnum.getByType(tableColumn.getColumnType());
                typeEnum = typeEnum == null ? DMColumnTypeEnum.VARCHAR : typeEnum;
                script.append(SQLConstants.TAB).append(typeEnum.buildModifyColumn(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                if (StringUtils.isNotBlank(tableColumn.getComment())&&!Objects.equals(EditStatusEnum.DELETE.toString(),editStatus)) {
                    script.append(SQLConstants.LINE_SEPARATOR).append(buildComment(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                }
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                DMIndexTypeEnum mysqlIndexTypeEnum = DMIndexTypeEnum.getByType(tableIndex.getType());
                if(mysqlIndexTypeEnum == null){
                    continue;
                }
                script.append(SQLConstants.TAB).append(mysqlIndexTypeEnum.buildModifyIndex(tableIndex)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
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
    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        script.append(quoteQualifiedIdentifier(databaseName, schemaName, tableName));
    }

    @Override
    protected void buildColumns(List<String> columnList, StringBuilder script) {
        if (CollectionUtils.isNotEmpty(columnList)) {
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS)
                    .append(columnList.stream().map(this::quoteIdentifier)
                            .collect(Collectors.joining(SQLConstants.COMMA)))
                    .append(SQLConstants.CLOSE_PARENTHESIS);
        }
    }

    @Override
    public String buildUpdate(UpdateSqlRequest request) {
        StringBuilder script = new StringBuilder("UPDATE ");
        buildTableName(request.getDatabaseName(), request.getSchemaName(), request.getTableName(), script);
        script.append(" SET ");
        script.append(request.getRow().entrySet().stream()
                .map(entry -> quoteIdentifier(entry.getKey()) + SQLConstants.EQUAL_SQL + entry.getValue())
                .collect(Collectors.joining(SQLConstants.COMMA)));
        if (MapUtils.isNotEmpty(request.getPrimaryKeyMap())) {
            script.append(" WHERE ");
            script.append(request.getPrimaryKeyMap().entrySet().stream()
                    .map(entry -> quoteIdentifier(entry.getKey()) + SQLConstants.EQUAL_SQL + entry.getValue())
                    .collect(Collectors.joining(SQLConstants.SQL_AND)));
        }
        return script.toString();
    }

    @Override
    public String buildCreateSchema(Schema schema) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQL_CREATE_SCHEMA).append(quoteIdentifier(schema.getName()));
        if(StringUtils.isNotBlank(schema.getOwner())){
            sqlBuilder.append(SQLConstants.SCHEMA_AUTHORIZATION_SQL).append(quoteIdentifier(schema.getOwner()));
        }

        return sqlBuilder.toString();
    }

    @Override
    public String buildExplain(String sql) {
        DMExecutableSql executableSql = parser.parseExecutableSql(sql);
        return executableSql.isExplain() ? sql : SQLConstants.EXPLAIN_SQL_PREFIX + sql;
    }

    private static String quoteStringLiteral(String value) {
        return DMIdentifierProcessor.INSTANCE.quoteStringLiteral(value);
    }
}
