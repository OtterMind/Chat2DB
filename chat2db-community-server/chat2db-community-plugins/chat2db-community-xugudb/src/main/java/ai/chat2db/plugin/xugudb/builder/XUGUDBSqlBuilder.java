package ai.chat2db.plugin.xugudb.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.xugudb.identifier.XugudbIdentifierProcessor;
import ai.chat2db.plugin.xugudb.enums.type.XUGUDBColumnTypeEnum;
import ai.chat2db.plugin.xugudb.enums.type.XUGUDBIndexTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.xugudb.constant.XUGUDBSqlBuilderConstants.*;
import static ai.chat2db.spi.constant.DefaultSqlBuilderConstants.SQL_AND;
import static ai.chat2db.spi.constant.DefaultSqlBuilderConstants.SQL_SET_2;
import static ai.chat2db.spi.constant.DefaultSqlBuilderConstants.SQL_UPDATE;
import static ai.chat2db.spi.constant.DefaultSqlBuilderConstants.SQL_WHERE_2;
public class XUGUDBSqlBuilder extends DefaultSqlBuilder {












    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();

        script.append(SQL_CREATE_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(XugudbIdentifierProcessor.escapeIdentifier(table.getSchemaName())).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(XugudbIdentifierProcessor.escapeIdentifier(table.getName())).append(VALUE_DOUBLE_QUOTE_OPEN_PAREN).append(SQLConstants.LINE_SEPARATOR);

        int columnCount = 0;
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            XUGUDBColumnTypeEnum typeEnum = XUGUDBColumnTypeEnum.getByType(column.getColumnType());
            typeEnum = typeEnum == null ? XUGUDBColumnTypeEnum.VARCHAR : typeEnum;
            script.append(SQLConstants.TAB).append(typeEnum.buildCreateColumnSql(column)).append(SQLConstants.COMMA_LINE_SEPARATOR);
            columnCount++;
        }
        if (columnCount == 0) {
            throw new IllegalArgumentException("XuguDB table requires at least one valid column");
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS_SEMICOLON);

        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            XUGUDBIndexTypeEnum indexTypeEnum = XUGUDBIndexTypeEnum.getByType(tableIndex.getType());
            if (indexTypeEnum == null) {
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

    private String buildTableComment(Table table) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_COMMENT_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(XugudbIdentifierProcessor.escapeIdentifier(table.getSchemaName())).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(XugudbIdentifierProcessor.escapeIdentifier(table.getName())).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(XugudbIdentifierProcessor.INSTANCE.escapeString(table.getComment())).append(SQLConstants.SINGLE_QUOTE);
        return script.toString();
    }

    private String buildComment(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_COMMENT_COLUMN).append(SQLConstants.DOUBLE_QUOTE).append(XugudbIdentifierProcessor.escapeIdentifier(column.getSchemaName())).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(XugudbIdentifierProcessor.escapeIdentifier(column.getTableName())).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(XugudbIdentifierProcessor.escapeIdentifier(column.getName())).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(XugudbIdentifierProcessor.INSTANCE.escapeString(column.getComment())).append(SQLConstants.SINGLE_QUOTE);
        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();

        if (!StringUtils.equals(oldTable.getName(), newTable.getName())) {
            script.append(SQL_ALTER_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(XugudbIdentifierProcessor.escapeIdentifier(oldTable.getSchemaName())).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(XugudbIdentifierProcessor.escapeIdentifier(oldTable.getName())).append(SQLConstants.DOUBLE_QUOTE);
            script.append(SQLConstants.SPACE).append(SQL_RENAME).append(SQLConstants.DOUBLE_QUOTE).append(XugudbIdentifierProcessor.escapeIdentifier(newTable.getName())).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append(SQLConstants.EMPTY).append(buildTableComment(newTable)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            String editStatus = tableColumn.getEditStatus();
            if (StringUtils.isNotBlank(editStatus)) {
                XUGUDBColumnTypeEnum typeEnum = XUGUDBColumnTypeEnum.getByType(tableColumn.getColumnType());
                typeEnum = typeEnum == null ? XUGUDBColumnTypeEnum.VARCHAR : typeEnum;
                script.append(SQLConstants.TAB).append(typeEnum.buildModifyColumn(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                if (StringUtils.isNotBlank(tableColumn.getComment())&&!Objects.equals(EditStatusEnum.DELETE.toString(),editStatus)) {
                    script.append(SQLConstants.LINE_SEPARATOR).append(buildComment(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                }
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                XUGUDBIndexTypeEnum mysqlIndexTypeEnum = XUGUDBIndexTypeEnum.getByType(tableIndex.getType());
                if (mysqlIndexTypeEnum == null) {
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
    public String buildCreateSchema(Schema schema) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQL_CREATE_SCHEMA+XugudbIdentifierProcessor.escapeIdentifier(schema.getName())+SQLConstants.DOUBLE_QUOTE);
        if(StringUtils.isNotBlank(schema.getOwner())){
            sqlBuilder.append(SQLConstants.SCHEMA_AUTHORIZATION_SQL).append(XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schema.getOwner()));
        }

        return sqlBuilder.toString();
    }

    @Override
    public String buildCreateDatabase(Database database) {
        return SQLConstants.CREATE_DATABASE_SQL_PREFIX + quoteIdentifier(database.getName());
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .map(XugudbIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                .collect(Collectors.joining(SQLConstants.DOT));
    }

    @Override
    public String buildSelectTable(String databaseName, String schemaName, String tableName) {
        return SQLConstants.SELECT_ALL_FROM_SQL_PREFIX + quoteQualifiedIdentifier(databaseName, schemaName, tableName);
    }

    @Override
    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        script.append(quoteQualifiedIdentifier(databaseName, schemaName, tableName));
    }

    @Override
    protected void buildColumns(List<String> columnList, StringBuilder script) {
        if (columnList != null && !columnList.isEmpty()) {
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS)
                    .append(columnList.stream()
                            .map(XugudbIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                            .collect(Collectors.joining(SQLConstants.COMMA)))
                    .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
        }
    }

    @Override
    public String buildUpdate(UpdateSqlRequest request) {
        StringBuilder script = new StringBuilder(SQL_UPDATE);
        buildTableName(request.getDatabaseName(), request.getSchemaName(), request.getTableName(), script);
        script.append(SQL_SET_2);
        script.append(request.getRow().entrySet().stream()
                .map(entry -> quoteIdentifier(entry.getKey()) + SQLConstants.EQUAL_SQL + entry.getValue())
                .collect(Collectors.joining(SQLConstants.COMMA)));
        if (MapUtils.isNotEmpty(request.getPrimaryKeyMap())) {
            script.append(SQL_WHERE_2);
            script.append(request.getPrimaryKeyMap().entrySet().stream()
                    .map(entry -> quoteIdentifier(entry.getKey()) + SQLConstants.EQUAL_SQL + entry.getValue())
                    .collect(Collectors.joining(SQL_AND)));
        }
        return script.toString();
    }
}
