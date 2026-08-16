package ai.chat2db.plugin.sundb.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.sundb.identifier.SUNDBIdentifierProcessor;
import ai.chat2db.plugin.sundb.enums.type.SUNDBColumnTypeEnum;
import ai.chat2db.plugin.sundb.enums.type.SUNDBIndexTypeEnum;
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

import static ai.chat2db.plugin.sundb.constant.SUNDBSqlBuilderConstants.*;
public class SUNDBSqlBuilder extends DefaultSqlBuilder {

    @Override
    public String quoteIdentifier(String identifier) {
        return SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        if (identifiers.length == 3) {
            String qualifier = StringUtils.isNotBlank(identifiers[1]) ? identifiers[1] : identifiers[0];
            return quoteQualifiedIdentifier(qualifier, identifiers[2]);
        }
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .map(SUNDBIdentifierProcessor.INSTANCE::quoteIdentifierAlways)
                .collect(Collectors.joining(SQLConstants.DOT));
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
            SUNDBColumnTypeEnum typeEnum = SUNDBColumnTypeEnum.getByType(column.getColumnType());
            typeEnum = typeEnum == null ? SUNDBColumnTypeEnum.INT : typeEnum;
            String createColumnSql = typeEnum.buildCreateColumnSql(column);
            script.append(SQLConstants.TAB).append(createColumnSql).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }

        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS_SEMICOLON);

        for (TableIndex tableIndex : table.getIndexList()) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            SUNDBIndexTypeEnum indexTypeEnum = SUNDBIndexTypeEnum.getByType(tableIndex.getType());
            if(indexTypeEnum!=null) {
                String indexScript = indexTypeEnum.buildIndexScript(tableIndex);
                script.append(SQLConstants.LINE_SEPARATOR).append(SQLConstants.EMPTY).append(indexScript).append(SQLConstants.SEMICOLON);
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
        script.append(SQL_COMMENT_TABLE)
                .append(quoteQualifiedIdentifier(table.getDatabaseName(), table.getSchemaName(), table.getName()))
                .append(" IS '").append(SUNDBIdentifierProcessor.INSTANCE.escapeString(table.getComment()))
                .append(SQLConstants.SINGLE_QUOTE);
        return script.toString();
    }

    private String buildComment(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_COMMENT_COLUMN)
                .append(quoteQualifiedIdentifier(column.getDatabaseName(), column.getSchemaName(),
                        column.getTableName()))
                .append(SQLConstants.DOT).append(quoteIdentifier(column.getName()))
                .append(" IS '").append(SUNDBIdentifierProcessor.INSTANCE.escapeString(column.getComment()))
                .append(SQLConstants.SINGLE_QUOTE);
        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();

        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
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
                SUNDBColumnTypeEnum typeEnum = SUNDBColumnTypeEnum.getByType(tableColumn.getColumnType());
                typeEnum = typeEnum == null ? SUNDBColumnTypeEnum.INT : typeEnum;
                script.append(SQLConstants.TAB);
                if (!typeEnum.buildModifyColumn(tableColumn).isEmpty()) {
                    script.append(typeEnum.buildModifyColumn(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                }

                if (StringUtils.isNotBlank(tableColumn.getComment())
                        && !Objects.equals(EditStatusEnum.DELETE.toString(), editStatus)
                        && !tableColumn.getComment().equals(tableColumn.getOldColumn().getComment())) {
                    script.append(SQLConstants.LINE_SEPARATOR).append(buildComment(tableColumn)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                }
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                SUNDBIndexTypeEnum mysqlIndexTypeEnum = SUNDBIndexTypeEnum.getByType(tableIndex.getType());
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
    public String buildCreateSchema(Schema schema) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQL_CREATE_SCHEMA).append(quoteIdentifier(schema.getName()));
        if (StringUtils.isNotBlank(schema.getOwner())) {
            sqlBuilder.append(SQLConstants.SCHEMA_AUTHORIZATION_SQL).append(quoteIdentifier(schema.getOwner()));
        }
        return sqlBuilder.toString();
    }

    @Override
    public String buildCreateDatabase(Database database) {
        return SQLConstants.CREATE_DATABASE_SQL_PREFIX + quoteIdentifier(database.getName());
    }

    @Override
    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        script.append(quoteQualifiedIdentifier(databaseName, schemaName, tableName));
    }

    @Override
    protected void buildColumns(List<String> columnList, StringBuilder script) {
        if (columnList != null && !columnList.isEmpty()) {
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS)
                    .append(columnList.stream().map(this::quoteIdentifier)
                            .collect(Collectors.joining(SQLConstants.COMMA)))
                    .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
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
                    .collect(Collectors.joining(" AND ")));
        }
        return script.toString();
    }
}
