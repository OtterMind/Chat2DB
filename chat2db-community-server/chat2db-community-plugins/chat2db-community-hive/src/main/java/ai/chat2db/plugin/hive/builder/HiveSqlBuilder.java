package ai.chat2db.plugin.hive.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.community.domain.api.enums.plugin.DmlTypeEnum;
import ai.chat2db.plugin.hive.HiveSqlGuards;
import ai.chat2db.plugin.hive.identifier.HiveIdentifierProcessor;
import ai.chat2db.plugin.hive.enums.type.HiveColumnTypeEnum;
import ai.chat2db.plugin.hive.enums.type.HiveIndexTypeEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.hive.constant.HiveSqlBuilderConstants.*;
public class HiveSqlBuilder extends DefaultSqlBuilder {

    @Override
    public String quoteIdentifier(String identifier) {
        return HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .map(HiveIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
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
                            .map(HiveIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
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
        String tableName = quoteQualifiedIdentifier(table.getDatabaseName(), table.getName());
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
        script.append(SQL_CREATE_TABLE);
        if (StringUtils.isNotBlank(table.getDatabaseName())) {
            script.append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(table.getDatabaseName())).append(SQLConstants.DOT);
        }
        script.append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(table.getName())).append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            script.append(SQLConstants.TAB).append(HiveColumnTypeEnum.buildCreateColumnSqlSafely(column))
                    .append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            HiveIndexTypeEnum hiveIndexTypeEnum = HiveIndexTypeEnum.getByType(tableIndex.getType());
            if (hiveIndexTypeEnum == null) {
                throw new IllegalArgumentException("Unsupported Hive index type: " + tableIndex.getType());
            }
            script.append(SQLConstants.TAB).append(SQLConstants.EMPTY).append(hiveIndexTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS);


        if (StringUtils.isNotBlank(table.getEngine())) {
            script.append(SQLConstants.ENGINE_SQL).append(HiveSqlGuards.requireHiveName(table.getEngine(), "engine"));
        }

        if (StringUtils.isNotBlank(table.getCharset())) {
            script.append(SQLConstants.DEFAULT_CHARACTER_SET_SQL).append(HiveSqlGuards.requireHiveName(table.getCharset(), "charset"));
        }

        if (StringUtils.isNotBlank(table.getCollate())) {
            script.append(SQLConstants.COLLATE_SQL).append(HiveSqlGuards.requireHiveName(table.getCollate(), "collation"));
        }

        if (table.getIncrementValue() != null) {
            script.append(SQLConstants.AUTO_INCREMENT_SQL).append(table.getIncrementValue());
        }

        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQL_COMMENT).append(HiveIdentifierProcessor.INSTANCE.escapeString(table.getComment())).append(SQLConstants.SINGLE_QUOTE);
        }

        if (StringUtils.isNotBlank(table.getPartition())) {
            script.append(VALUE_LOCAL_SQL_PART).append(HiveSqlGuards.requirePartitionClause(table.getPartition()));
        }
        script.append(SQLConstants.SEMICOLON);

        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();
        boolean isModify = false;
        script.append(SQL_ALTER_TABLE);
        if (StringUtils.isNotBlank(newTable.getDatabaseName())) {
            script.append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTable.getDatabaseName())).append(SQLConstants.DOT);
        }
        script.append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(oldTable.getName())).append(SQLConstants.LINE_SEPARATOR);
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQLConstants.TAB).append(SQL_RENAME).append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTable.getName())).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            isModify = true;
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            if (isModify) {
                script.append(SQL_ALTER_TABLE);
                if (StringUtils.isNotBlank(newTable.getDatabaseName())) {
                    script.append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTable.getDatabaseName())).append(SQLConstants.DOT);
                }
                script.append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(newTable.getName())).append(SQLConstants.LINE_SEPARATOR);
            }
            script.append(SQLConstants.TAB).append(SQL_SET_TBLPROPERTIES_COMMENT).append(SQLConstants.SINGLE_QUOTE).append(HiveIdentifierProcessor.INSTANCE.escapeString(newTable.getComment())).append(VALUE_SINGLE_QUOTE_CLOSE_PAREN_COMMA);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus()) && StringUtils.isNotBlank(tableColumn.getColumnType()) && StringUtils.isNotBlank(tableColumn.getName())) {
                script.append(SQLConstants.TAB).append(HiveColumnTypeEnum.buildModifyColumnSafely(tableColumn))
                        .append(SQLConstants.COMMA_LINE_SEPARATOR);
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                HiveIndexTypeEnum hiveIndexTypeEnum = HiveIndexTypeEnum.getByType(tableIndex.getType());
                if (hiveIndexTypeEnum == null) {
                    throw new IllegalArgumentException("Unsupported Hive index type: " + tableIndex.getType());
                }
                script.append(SQLConstants.TAB).append(hiveIndexTypeEnum.buildModifyIndex(tableIndex)).append(SQLConstants.COMMA_LINE_SEPARATOR);
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
        StringBuilder sqlBuilder = new StringBuilder(sql.length() + 14);
        sqlBuilder.append(sql);
        if (offset == 0) {
            sqlBuilder.append(SQLConstants.LINE_SEPARATOR_LIMIT_SQL);
            sqlBuilder.append(pageSize);
        } else {
            sqlBuilder.append(SQLConstants.LINE_SEPARATOR_LIMIT_SQL);
            sqlBuilder.append(offset);
            sqlBuilder.append(SQLConstants.COMMA);
            sqlBuilder.append(pageSize);
        }
        return sqlBuilder.toString();
    }


    @Override
    public String buildCreateDatabase(Database database) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQL_CREATE_DATABASE).append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database.getName()));
        if (StringUtils.isNotBlank(database.getComment())) {
            sqlBuilder.append(SQL_COMMENT_SINGLE_QUOTE).append(HiveIdentifierProcessor.INSTANCE.escapeString(database.getComment())).append(SQLConstants.SINGLE_QUOTE);

        }
        return sqlBuilder.toString();
    }

}
