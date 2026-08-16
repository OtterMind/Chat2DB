package ai.chat2db.plugin.sundb.enums.type;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.sundb.SUNDBSqlGuards;
import ai.chat2db.plugin.sundb.identifier.SUNDBIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

import static ai.chat2db.plugin.sundb.constant.SUNDBIndexTypeEnumConstants.*;
public enum SUNDBIndexTypeEnum {

    PRIMARY_KEY("Primary", "PRIMARY KEY"),

    NORMAL("Normal", "INDEX"),

    UNIQUE("Unique", "UNIQUE INDEX"),

    BITMAP("BITMAP", "BITMAP INDEX");










    public IndexType getIndexType() {
        return indexType;
    }

    public void setIndexType(IndexType indexType) {
        this.indexType = indexType;
    }

    private IndexType indexType;


    public String getName() {
        return name;
    }

    private String name;


    public String getKeyword() {
        return keyword;
    }

    private String keyword;

    SUNDBIndexTypeEnum(String name, String keyword) {
        this.name = name;
        this.keyword = keyword;
        this.indexType = new IndexType(name);
    }


    public static SUNDBIndexTypeEnum getByType(String type) {
        for (SUNDBIndexTypeEnum value : SUNDBIndexTypeEnum.values()) {
            if (value.name.equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }

    public String buildIndexScript(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        if (PRIMARY_KEY.equals(this)) {
            script.append(SQL_ALTER_TABLE)
                    .append(SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getSchemaName()))
                    .append(".")
                    .append(SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getTableName()))
                    .append(" ADD PRIMARY KEY ").append(buildIndexColumn(tableIndex));
        } else {
            if (UNIQUE.equals(this)) {
                script.append(SQL_CREATE_UNIQUE_INDEX);
            } else {
                script.append(SQL_CREATE_INDEX);
            }
            script.append(buildIndexName(tableIndex)).append(SQL_ON)
                    .append(SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getSchemaName()))
                    .append(".")
                    .append(SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getTableName()))
                    .append(" ").append(buildIndexColumn(tableIndex));
        }
        return script.toString();
    }


    private String buildIndexColumn(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("(");
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            if (StringUtils.isNotBlank(column.getColumnName())) {
                script.append(SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getColumnName()));
                if (!StringUtils.isBlank(column.getAscOrDesc()) && !PRIMARY_KEY.equals(this)) {
                    script.append(" ").append(SUNDBSqlGuards.requireAscOrDesc(column.getAscOrDesc()));
                }
                script.append(",");
            }
        }
        script.deleteCharAt(script.length() - 1);
        script.append(")");
        return script.toString();
    }

    private String buildIndexName(TableIndex tableIndex) {
        return SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getSchemaName()) + "." + SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getName());
    }

    public String buildModifyIndex(TableIndex tableIndex) {
        if (EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return buildDropIndex(tableIndex);
        }
        if (EditStatusEnum.MODIFY.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(buildDropIndex(tableIndex), ";\n", buildIndexScript(tableIndex));
        }
        if (EditStatusEnum.ADD.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(buildIndexScript(tableIndex));
        }
        return "";
    }

    private String buildDropIndex(TableIndex tableIndex) {
        if (SUNDBIndexTypeEnum.PRIMARY_KEY.getName().equals(tableIndex.getType())) {
            String tableName = SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getSchemaName()) + "." + SUNDBIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getTableName());
            return StringUtils.join(SQL_ALTER_TABLE,tableName,SQL_DROP_PRIMARY_KEY);
        }
        StringBuilder script = new StringBuilder();
        script.append(SQL_DROP_INDEX);
        script.append(buildIndexName(tableIndex));

        return script.toString();
    }

    public static List<IndexType> getIndexTypes() {
        return Arrays.asList(SUNDBIndexTypeEnum.values()).stream().map(SUNDBIndexTypeEnum::getIndexType).collect(java.util.stream.Collectors.toList());
    }
}
