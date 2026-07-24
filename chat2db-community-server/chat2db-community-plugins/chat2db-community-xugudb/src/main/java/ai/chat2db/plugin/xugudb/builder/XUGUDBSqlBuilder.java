package ai.chat2db.plugin.xugudb.builder;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.plugin.xugudb.enums.type.XUGUDBColumnTypeEnum;
import ai.chat2db.plugin.xugudb.enums.type.XUGUDBIndexTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

import static ai.chat2db.plugin.xugudb.constant.XUGUDBSqlBuilderConstants.*;
public class XUGUDBSqlBuilder extends DefaultSqlBuilder {












    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();

        script.append(SQL_CREATE_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(table.getSchemaName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(table.getName()).append(VALUE_DOUBLE_QUOTE_OPEN_PAREN).append(SQLConstants.LINE_SEPARATOR);

        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            XUGUDBColumnTypeEnum typeEnum = XUGUDBColumnTypeEnum.getByType(column.getColumnType());
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
            XUGUDBIndexTypeEnum indexTypeEnum = XUGUDBIndexTypeEnum.getByType(tableIndex.getType());
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
        script.append(SQL_COMMENT_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(table.getSchemaName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(table.getName()).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(table.getComment()).append(SQLConstants.SINGLE_QUOTE);
        return script.toString();
    }

    private String buildComment(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_COMMENT_COLUMN).append(SQLConstants.DOUBLE_QUOTE).append(column.getSchemaName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(column.getTableName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(column.getName()).append(VALUE_DOUBLE_QUOTE_IS_SINGLE_QUOTE).append(column.getComment()).append(SQLConstants.SINGLE_QUOTE);
        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();

        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQL_ALTER_TABLE).append(SQLConstants.DOUBLE_QUOTE).append(oldTable.getSchemaName()).append(SQLConstants.DOUBLE_QUOTE_DOT_DOUBLE_QUOTE).append(oldTable.getName()).append(SQLConstants.DOUBLE_QUOTE);
            script.append(SQLConstants.SPACE).append(SQL_RENAME).append(SQLConstants.DOUBLE_QUOTE).append(newTable.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append(SQLConstants.EMPTY).append(buildTableComment(newTable)).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            String editStatus = tableColumn.getEditStatus();
            if (StringUtils.isNotBlank(editStatus)) {
                XUGUDBColumnTypeEnum typeEnum = XUGUDBColumnTypeEnum.getByType(tableColumn.getColumnType());
                if (typeEnum == null) {
                    continue;
                }
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
        sqlBuilder.append(SQL_CREATE_SCHEMA+schema.getName()+SQLConstants.DOUBLE_QUOTE);
        if(StringUtils.isNotBlank(schema.getOwner())){
            sqlBuilder.append(SQLConstants.SCHEMA_AUTHORIZATION_SQL).append(schema.getOwner());
        }

        return sqlBuilder.toString();
    }
}
