package ai.chat2db.plugin.clickhouse.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.clickhouse.ClickHouseSqlGuards;
import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseColumnTypeEnum;
import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseIndexTypeEnum;
import ai.chat2db.plugin.clickhouse.identifier.ClickHouseIdentifierProcessor;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


import static ai.chat2db.plugin.clickhouse.constant.ClickHouseSqlBuilderConstants.*;
public class ClickHouseSqlBuilder extends DefaultSqlBuilder {

    @Override
    public String quoteIdentifier(String identifier) {
        return ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        if (identifiers.length == 3) {
            String qualifier = StringUtils.isNotBlank(identifiers[1]) ? identifiers[1] : identifiers[0];
            return quoteQualifiedIdentifier(qualifier, identifiers[2]);
        }
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .map(ClickHouseIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
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
                    .append(columnList.stream()
                            .map(ClickHouseIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                            .collect(Collectors.joining(SQLConstants.COMMA)))
                    .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
        }
    }





    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_CREATE_TABLE);
        script.append(quoteQualifiedIdentifier(table.getDatabaseName(), table.getSchemaName(), table.getName()))
                .append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);
        List<String> definitions = new ArrayList<>();
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            definitions.add(ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(column).stripTrailing());
        }
        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            ClickHouseIndexTypeEnum indexType = ClickHouseIndexTypeEnum.getByType(tableIndex.getType());
            if (indexType != null && !ClickHouseIndexTypeEnum.PRIMARY.equals(indexType)) {
                definitions.add(indexType.buildIndexScript(tableIndex).stripTrailing());
            }
        }
        script.append(definitions.stream()
                .map(definition -> SQLConstants.TAB + definition)
                .collect(Collectors.joining(SQLConstants.COMMA_LINE_SEPARATOR)));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS);


        if (StringUtils.isNotBlank(table.getEngine())) {
            script.append(SQLConstants.ENGINE_SQL).append(ClickHouseSqlGuards.requireEngine(table.getEngine())).append(SQLConstants.LINE_SEPARATOR);
        }
        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            ClickHouseIndexTypeEnum indexType = ClickHouseIndexTypeEnum.getByType(tableIndex.getType());
            if (ClickHouseIndexTypeEnum.PRIMARY.equals(indexType)) {
                script.append(SQLConstants.TAB).append(SQLConstants.EMPTY)
                        .append(indexType.buildIndexScript(tableIndex)).append(SQLConstants.LINE_SEPARATOR);
            }
        }

        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQL_COMMENT).append(ClickHouseIdentifierProcessor.INSTANCE.escapeString(table.getComment())).append(SQLConstants.SINGLE_QUOTE);
        }

        script.append(SQLConstants.SEMICOLON);

        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        List<String> actions = new ArrayList<>();

        if (!StringUtils.equals(oldTable.getComment(), newTable.getComment())) {
            actions.add(SQL_MODIFY_COMMENT + SQLConstants.SPACE + SQLConstants.SINGLE_QUOTE
                    + ClickHouseIdentifierProcessor.INSTANCE.escapeString(StringUtils.defaultString(newTable.getComment()))
                    + SQLConstants.SINGLE_QUOTE);
        }
        if (newTable.getColumnList() != null) {
            for (TableColumn tableColumn : newTable.getColumnList()) {
                if (StringUtils.isNotBlank(tableColumn.getEditStatus())
                        && StringUtils.isNotBlank(tableColumn.getColumnType())
                        && StringUtils.isNotBlank(tableColumn.getName())) {
                    String action = ClickHouseColumnTypeEnum.buildModifyColumnSqlSafely(tableColumn);
                    if (StringUtils.isNotBlank(action)) {
                        actions.add(action);
                    }
                }
            }
        }
        if (newTable.getIndexList() != null) {
            for (TableIndex tableIndex : newTable.getIndexList()) {
                if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                    ClickHouseIndexTypeEnum indexType = ClickHouseIndexTypeEnum.getByType(tableIndex.getType());
                    if (indexType != null) {
                        String action = indexType.buildModifyIndex(tableIndex);
                        if (StringUtils.isNotBlank(action)) {
                            actions.add(action);
                        }
                    }
                }
            }
        }

        if (actions.isEmpty()) {
            return "";
        }
        return SQL_ALTER_TABLE
                + quoteQualifiedIdentifier(oldTable.getDatabaseName(), oldTable.getSchemaName(), oldTable.getName())
                + SQLConstants.LINE_SEPARATOR + SQLConstants.TAB
                + String.join(SQLConstants.COMMA_LINE_SEPARATOR + SQLConstants.TAB, actions)
                + SQLConstants.SEMICOLON;
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
        sqlBuilder.append(SQL_CREATE_DATABASE).append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database.getName()));
        if(StringUtils.isNotBlank(database.getComment())){
            sqlBuilder.append(SQL_SEMICOLON_ALTER_DATABASE).append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database.getName())).append(SQL_COMMENT).append(ClickHouseIdentifierProcessor.INSTANCE.escapeString(database.getComment())).append(SQLConstants.SINGLE_QUOTE_SEMICOLON);
        }
        return sqlBuilder.toString();
    }

    @Override
    public String buildCreateSchema(Schema schema) {
        return SQL_CREATE_DATABASE + quoteIdentifier(schema.getName());
    }

    @Override
    public String buildDropSchema(String schemaName) {
        return "DROP DATABASE " + quoteIdentifier(schemaName);
    }

}
