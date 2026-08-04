package ai.chat2db.plugin.snowflake.builder;

import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.community.domain.api.enums.plugin.DmlTypeEnum;
import ai.chat2db.plugin.snowflake.SnowflakeSqlGuards;
import ai.chat2db.plugin.snowflake.identifier.SnowflakeIdentifierProcessor;
import ai.chat2db.plugin.snowflake.enums.type.SnowflakeColumnTypeEnum;
import ai.chat2db.plugin.snowflake.enums.type.SnowflakeIndexTypeEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.snowflake.constant.SnowflakeSqlBuilderConstants.*;
public class SnowflakeSqlBuilder extends DefaultSqlBuilder {

    @Override
    public String quoteIdentifier(String identifier) {
        return SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .map(SnowflakeIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                .collect(Collectors.joining(SQLConstants.DOT));
    }

    @Override
    public String quoteAlias(String alias) {
        return quoteIdentifier(alias);
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
                            .map(SnowflakeIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                            .collect(Collectors.joining(SQLConstants.COMMA)))
                    .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
        }
    }

    @Override
    public String buildUpdate(UpdateSqlRequest request) {
        StringBuilder script = new StringBuilder(SQLConstants.UPDATE_KEYWORD + SQLConstants.SPACE);
        buildTableName(request.getDatabaseName(), request.getSchemaName(), request.getTableName(), script);
        script.append(" SET ").append(request.getRow().entrySet().stream()
                .map(entry -> quoteIdentifier(entry.getKey()) + SQLConstants.EQUAL_SQL + entry.getValue())
                .collect(Collectors.joining(SQLConstants.COMMA)));
        if (MapUtils.isNotEmpty(request.getPrimaryKeyMap())) {
            script.append(" WHERE ").append(request.getPrimaryKeyMap().entrySet().stream()
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
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig){
        StringBuilder script = new StringBuilder();
        script.append(SQL_CREATE_TABLE);
        if (StringUtils.isNotBlank(table.getSchemaName())) {
            script.append(SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(table.getSchemaName())).append(SQLConstants.DOT);
        }
        script.append(SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(table.getName())).append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            SnowflakeColumnTypeEnum typeEnum = requireColumnType(column.getColumnType());
            script.append(SQLConstants.TAB).append(typeEnum.buildCreateColumnSql(column)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            SnowflakeIndexTypeEnum mysqlIndexTypeEnum = requireIndexType(tableIndex.getType());
            script.append(SQLConstants.TAB).append(SQLConstants.EMPTY).append(mysqlIndexTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS);


        if (StringUtils.isNotBlank(table.getEngine())) {
            script.append(SQLConstants.ENGINE_SQL).append(SnowflakeSqlGuards.requireSnowflakeName(table.getEngine(), "engine"));
        }

        if (StringUtils.isNotBlank(table.getCharset())) {
            script.append(SQLConstants.DEFAULT_CHARACTER_SET_SQL).append(SnowflakeSqlGuards.requireSnowflakeName(table.getCharset(), "charset"));
        }

        if (StringUtils.isNotBlank(table.getCollate())) {
            script.append(SQLConstants.COLLATE_SQL).append(SnowflakeSqlGuards.requireSnowflakeName(table.getCollate(), "collation"));
        }

        if (table.getIncrementValue() != null) {
            script.append(SQLConstants.AUTO_INCREMENT_SQL).append(table.getIncrementValue());
        }

        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQL_COMMENT).append(SnowflakeIdentifierProcessor.INSTANCE.escapeString(table.getComment())).append(SQLConstants.SINGLE_QUOTE);
        }

        if (StringUtils.isNotBlank(table.getPartition())) {
            script.append(VALUE_LOCAL_SQL_PART).append(SnowflakeSqlGuards.requireClusterByClause(table.getPartition()));
        }
        script.append(SQLConstants.SEMICOLON);

        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_ALTER_TABLE);
        script.append(SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(oldTable.getName())).append(SQLConstants.LINE_SEPARATOR);
        boolean isChangeTableName = false;
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQL_RENAME).append(SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTable.getName())).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            isChangeTableName = true;
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            if (isChangeTableName) {
                script.append(SQL_ALTER_TABLE);
                script.append(SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTable.getName())).append(SQLConstants.LINE_SEPARATOR);
                script.append(SQLConstants.TAB).append(SQL_SET_COMMENT).append(SQLConstants.SINGLE_QUOTE).append(SnowflakeIdentifierProcessor.INSTANCE.escapeString(newTable.getComment())).append(SQLConstants.SINGLE_QUOTE).append(SQLConstants.COMMA_LINE_SEPARATOR);
            } else {
                script.append(SQLConstants.TAB).append(SQL_SET_COMMENT).append(SQLConstants.SINGLE_QUOTE).append(SnowflakeIdentifierProcessor.INSTANCE.escapeString(newTable.getComment())).append(SQLConstants.SINGLE_QUOTE).append(SQLConstants.COMMA_LINE_SEPARATOR);
            }
        }
        if (!Objects.equals(oldTable.getIncrementValue(), newTable.getIncrementValue())) {
            script.append(SQLConstants.TAB).append(SQL_AUTO_INCREMENT_EQUAL).append(newTable.getIncrementValue()).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus()) && StringUtils.isNotBlank(tableColumn.getColumnType()) && StringUtils.isNotBlank(tableColumn.getName())) {
                SnowflakeColumnTypeEnum typeEnum = requireColumnType(tableColumn.getColumnType());
                script.append(SQLConstants.TAB).append(typeEnum.buildModifyColumn(tableColumn)).append(SQLConstants.COMMA_LINE_SEPARATOR);
            }
        }

        if (script.length() > 2) {
            script = new StringBuilder(script.substring(0, script.length() - 2));
            script.append(SQLConstants.SEMICOLON);
        }

        return script.toString();
    }

    private SnowflakeColumnTypeEnum requireColumnType(String columnType) {
        SnowflakeColumnTypeEnum typeEnum = SnowflakeColumnTypeEnum.getByType(columnType);
        if (typeEnum == null) {
            throw new IllegalArgumentException("Unsupported Snowflake column type: " + columnType);
        }
        return typeEnum;
    }

    private SnowflakeIndexTypeEnum requireIndexType(String indexType) {
        SnowflakeIndexTypeEnum typeEnum = SnowflakeIndexTypeEnum.getByType(indexType);
        if (typeEnum == null) {
            throw new IllegalArgumentException("Unsupported Snowflake index type: " + indexType);
        }
        return typeEnum;
    }

}
