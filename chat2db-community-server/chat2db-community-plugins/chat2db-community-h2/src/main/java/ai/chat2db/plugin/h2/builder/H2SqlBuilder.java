package ai.chat2db.plugin.h2.builder;

import ai.chat2db.community.domain.api.enums.plugin.DmlTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.enums.plugin.IndexTypeEnum;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.h2.identifier.H2IdentifierProcessor;
import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.plugin.h2.H2SqlGuards;

import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.h2.constant.H2SqlBuilderConstants.*;
public class H2SqlBuilder extends DefaultSqlBuilder  {

    @Override
    public String quoteIdentifier(String identifier) {
        return H2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        return Arrays.stream(identifiers)
            .filter(StringUtils::isNotBlank)
            .map(H2IdentifierProcessor.INSTANCE::quoteIdentifierAlways)
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
                .append(columnList.stream().map(H2IdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                    .collect(Collectors.joining(SQLConstants.COMMA)))
                .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
        }
    }

    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();
        script.append(SQLConstants.CREATE_TABLE_SQL_PREFIX);
        script.append(quoteIdentifier(table.getName()))
            .append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);

        List<String> comments = new ArrayList<>();
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            script.append(SQLConstants.TAB).append(SQLConstants.SPACE)
                .append(quoteIdentifier(column.getName())).append(SQLConstants.SPACE)
                .append(H2SqlGuards.requireSafeTypeName(column.getColumnType()));
            if (column.getColumnSize() != null) {
                script.append(SQLConstants.OPEN_PARENTHESIS).append(column.getColumnSize());
                if (column.getDecimalDigits() != null) {
                    script.append(SQLConstants.COMMA).append(column.getDecimalDigits());
                }
                script.append(SQLConstants.CLOSE_PARENTHESIS);
            }

            if (column.getNullable() != null && 1 == column.getNullable()) {
                script.append(SQLConstants.NULL_SQL);
            } else {
                script.append(SQLConstants.NOT_NULL_SQL_WITH_PREFIX);
            }
            if (StringUtils.isNotBlank(column.getComment())) {
                comments.add(generateCommentSql(column));
            }
            script.append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS);
        script.append(SQLConstants.SEMICOLON);
        if (CollectionUtils.isNotEmpty(comments)) {
            script.append(SQLConstants.LINE_SEPARATOR);
            comments.forEach(script::append);
        }
        if (CollectionUtils.isNotEmpty(table.getIndexList())) {
            script.append(generateIndexSql(table.getIndexList()));
        }
        return script.toString();
    }

    private String generateCommentSql(TableColumn column) {
        return SQLConstants.COMMENT_ON_COLUMN_SQL_PREFIX
            + quoteIdentifier(column.getTableName()) + SQLConstants.DOT + quoteIdentifier(column.getName())
            + SQLConstants.SQL_IS_SINGLE_QUOTE
            + H2IdentifierProcessor.INSTANCE.escapeString(column.getComment())
            + SQLConstants.SINGLE_QUOTE_SEMICOLON_LINE_SEPARATOR;
    }

    private String generateIndexSql(List<TableIndex> indexList) {
        StringBuilder script = new StringBuilder();
        for (TableIndex index : indexList) {
            List<TableIndexColumn> columnList = index.getColumnList();
            if (CollectionUtils.isEmpty(columnList)) {
                continue;
            }
            script.append(SQLConstants.CREATE_SQL_PREFIX);
            if (IndexTypeEnum.UNIQUE.getName().equals(index.getType())) {
                script.append(SQLConstants.UNIQUE_SQL);
            }
            script.append(SQLConstants.INDEX_SQL);
            script.append(quoteIdentifier(index.getName()));
            script.append(SQLConstants.SQL_ON);
            script.append(quoteIdentifier(index.getTableName()));
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS);
            script.append(columnList.stream()
                .map(column -> quoteIdentifier(column.getColumnName()))
                .collect(Collectors.joining(SQLConstants.COMMA)));
            script.append(SQLConstants.CLOSE_PARENTHESIS_SEMICOLON_LINE_SEPARATOR);
        }
        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQLConstants.ALTER_TABLE_SQL_PREFIX).append(quoteIdentifier(oldTable.getName()))
                .append(SQLConstants.TABLE_RENAME_SQL).append(quoteIdentifier(newTable.getName()))
                .append(SQLConstants.SEMICOLON).append(SQLConstants.LINE_SEPARATOR);
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append(SQLConstants.COMMENT_ON_TABLE_SQL_PREFIX).append(quoteIdentifier(newTable.getName()))
                .append(SQLConstants.SQL_IS_SINGLE_QUOTE)
                .append(H2IdentifierProcessor.INSTANCE.escapeString(newTable.getComment()))
                .append(SQLConstants.SINGLE_QUOTE_SEMICOLON).append(SQLConstants.LINE_SEPARATOR);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus()) && StringUtils.isNotBlank(
                tableColumn.getColumnType()) && StringUtils.isNotBlank(tableColumn.getName())) {
                script.append(generateAlterColumnSql(tableColumn)).append(SQLConstants.LINE_SEPARATOR);
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                script.append(generateAlterIndexSql(tableIndex)).append(SQLConstants.LINE_SEPARATOR);
            }
        }
        return script.toString();
    }

    private String generateAlterColumnSql(TableColumn tableColumn) {
        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            return SQLConstants.ALTER_TABLE_SQL_PREFIX + quoteIdentifier(tableColumn.getTableName())
                + " DROP COLUMN " + quoteIdentifier(tableColumn.getName())
                + SQLConstants.SEMICOLON;
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            return SQLConstants.ALTER_TABLE_SQL_PREFIX + quoteIdentifier(tableColumn.getTableName())
                + " ADD COLUMN " + quoteIdentifier(tableColumn.getName())
                + SQLConstants.SPACE + H2SqlGuards.requireSafeTypeName(tableColumn.getColumnType()) + SQLConstants.SEMICOLON;
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            return SQLConstants.ALTER_TABLE_SQL_PREFIX + quoteIdentifier(tableColumn.getTableName())
                + " MODIFY COLUMN " + quoteIdentifier(tableColumn.getName())
                + SQLConstants.SPACE + H2SqlGuards.requireSafeTypeName(tableColumn.getColumnType()) + SQLConstants.SEMICOLON;
        }
        if (tableColumn.getComment() != null) {
            return generateCommentSql(tableColumn);
        }
        return SQLConstants.EMPTY;
    }

    private String generateAlterIndexSql(TableIndex tableIndex) {
        if (EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return SQLConstants.DROP_INDEX_SQL_PREFIX + quoteIdentifier(tableIndex.getName())
                + SQLConstants.SEMICOLON;
        }
        if (EditStatusEnum.ADD.name().equals(tableIndex.getEditStatus())) {
            boolean unique = IndexTypeEnum.UNIQUE.getName().equals(tableIndex.getType());
            String columnNames = tableIndex.getColumnList().stream()
                .map(column -> quoteIdentifier(column.getColumnName()))
                .collect(Collectors.joining(SQLConstants.COMMA + SQLConstants.SPACE));
            return SQLConstants.CREATE_SQL_PREFIX + (unique ? SQLConstants.UNIQUE_SQL : SQLConstants.EMPTY)
                + SQLConstants.INDEX_SQL + quoteIdentifier(tableIndex.getName())
                + SQLConstants.SQL_ON + quoteIdentifier(tableIndex.getTableName())
                + SQLConstants.SPACE_OPEN_PARENTHESIS + columnNames
                + SQLConstants.CLOSE_PARENTHESIS + SQLConstants.SEMICOLON;
        }
        return SQLConstants.EMPTY;
    }

    @Override
    public String buildUpdate(UpdateSqlRequest request) {
        Map<String, String> row = request.getRow();
        Map<String, String> primaryKeyMap = request.getPrimaryKeyMap();
        StringBuilder script = new StringBuilder();
        script.append(SQLConstants.UPDATE_SQL_PREFIX);
        buildTableName(request.getDatabaseName(), request.getSchemaName(), request.getTableName(), script);

        script.append(SQLConstants.SET_SQL);
        List<String> setClauses = row.entrySet().stream()
            .map(entry -> quoteIdentifier(entry.getKey()) + SQLConstants.EQUAL_SQL + entry.getValue())
            .collect(Collectors.toList());
        script.append(String.join(SQLConstants.COMMA, setClauses));

        if (MapUtils.isNotEmpty(primaryKeyMap)) {
            script.append(SQLConstants.WHERE_SQL);
            List<String> whereClauses = primaryKeyMap.entrySet().stream()
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
        String tableName = quoteIdentifier(table.getName());
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
        } else if (DmlTypeEnum.UPDATE.name().equalsIgnoreCase(type)) {
            String setClause = columnNames.stream()
                .map(name -> name + SQLConstants.EQUAL_SQL + SQLConstants.SPACE)
                .collect(Collectors.joining(SQLConstants.COMMA));
            return SQLConstants.UPDATE_SQL_PREFIX + tableName + SQLConstants.SET_SQL_LOWER
                + setClause + SQLConstants.WHERE_SQL_LOWER;
        } else if (DmlTypeEnum.DELETE.name().equalsIgnoreCase(type)) {
            return SQLConstants.DELETE_FROM_SQL_PREFIX + tableName + SQLConstants.WHERE_SQL_LOWER;
        } else if (DmlTypeEnum.SELECT.name().equalsIgnoreCase(type)) {
            return SQLConstants.SELECT_SQL_PREFIX + String.join(SQLConstants.COMMA, columnNames)
                + SQLConstants.FROM_WHERE_SQL + tableName;
        }
        return SQLConstants.EMPTY;
    }

    @Override
    public String buildCreateDatabase(Database database) {
        return SQLConstants.CREATE_DATABASE_SQL_PREFIX + quoteIdentifier(database.getName());
    }

    @Override
    public String buildCreateSchema(Schema schema) {
        StringBuilder sqlBuilder = new StringBuilder();
        String quotedSchemaName = quoteIdentifier(schema.getName());
        sqlBuilder.append(SQL_CREATE_SCHEMA).append(quotedSchemaName).append(SQLConstants.SEMICOLON);

        if (StringUtils.isNotBlank(schema.getComment())) {
            sqlBuilder.append(SQL_COMMENT_ON_SCHEMA).append(quotedSchemaName).append(VALUE_IS_SINGLE_QUOTE)
                .append(H2IdentifierProcessor.INSTANCE.escapeString(schema.getComment()))
                .append(SQLConstants.SINGLE_QUOTE_SEMICOLON);
        }

        return sqlBuilder.toString();
    }

}
