package ai.chat2db.plugin.snowflake.builder;

import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.plugin.snowflake.enums.type.SnowflakeColumnTypeEnum;
import ai.chat2db.plugin.snowflake.enums.type.SnowflakeIndexTypeEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

import static ai.chat2db.plugin.snowflake.constant.SnowflakeSqlBuilderConstants.*;
public class SnowflakeSqlBuilder extends DefaultSqlBuilder {








    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig){
        StringBuilder script = new StringBuilder();
        script.append(SQL_CREATE_TABLE);
        if (StringUtils.isNotBlank(table.getSchemaName())) {
            script.append(table.getSchemaName()).append(SQLConstants.DOT);
        }
        script.append(SQLConstants.DOUBLE_QUOTE).append(table.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);
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
            script.append(SQLConstants.ENGINE_SQL).append(table.getEngine());
        }

        if (StringUtils.isNotBlank(table.getCharset())) {
            script.append(SQLConstants.DEFAULT_CHARACTER_SET_SQL).append(table.getCharset());
        }

        if (StringUtils.isNotBlank(table.getCollate())) {
            script.append(SQLConstants.COLLATE_SQL).append(table.getCollate());
        }

        if (table.getIncrementValue() != null) {
            script.append(SQLConstants.AUTO_INCREMENT_SQL).append(table.getIncrementValue());
        }

        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQL_COMMENT).append(table.getComment()).append(SQLConstants.SINGLE_QUOTE);
        }

        if (StringUtils.isNotBlank(table.getPartition())) {
            script.append(VALUE_LOCAL_SQL_PART).append(table.getPartition());
        }
        script.append(SQLConstants.SEMICOLON);

        return script.toString();
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder script = new StringBuilder();
        script.append(SQL_ALTER_TABLE);
        script.append(SQLConstants.DOUBLE_QUOTE).append(oldTable.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.LINE_SEPARATOR);
        boolean isChangeTableName = false;
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQL_RENAME).append(SQLConstants.DOUBLE_QUOTE).append(newTable.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            isChangeTableName = true;
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            if (isChangeTableName) {
                script.append(SQL_ALTER_TABLE);
                script.append(SQLConstants.DOUBLE_QUOTE).append(newTable.getName()).append(SQLConstants.DOUBLE_QUOTE).append(SQLConstants.LINE_SEPARATOR);
                script.append(SQLConstants.TAB).append(SQL_SET_COMMENT).append(SQLConstants.SINGLE_QUOTE).append(newTable.getComment()).append(SQLConstants.SINGLE_QUOTE).append(SQLConstants.COMMA_LINE_SEPARATOR);
            } else {
                script.append(SQLConstants.TAB).append(SQL_SET_COMMENT).append(SQLConstants.SINGLE_QUOTE).append(newTable.getComment()).append(SQLConstants.SINGLE_QUOTE).append(SQLConstants.COMMA_LINE_SEPARATOR);
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
