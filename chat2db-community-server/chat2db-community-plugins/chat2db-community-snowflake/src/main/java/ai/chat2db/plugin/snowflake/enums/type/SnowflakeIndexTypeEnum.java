package ai.chat2db.plugin.snowflake.enums.type;

import ai.chat2db.plugin.snowflake.SnowflakeSqlGuards;
import ai.chat2db.plugin.snowflake.identifier.SnowflakeIdentifierProcessor;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

import static ai.chat2db.plugin.snowflake.constant.SnowflakeIndexTypeEnumConstants.*;
public enum SnowflakeIndexTypeEnum {

    PRIMARY_KEY("Primary", "PRIMARY KEY"),

    NORMAL("Normal", "INDEX"),

    UNIQUE("Unique", "UNIQUE INDEX"),

    FULLTEXT("Fulltext", "FULLTEXT INDEX"),

    SPATIAL("Spatial", "SPATIAL INDEX");





    public String getName() {
        return name;
    }

    private String name;


    public String getKeyword() {
        return keyword;
    }

    private String keyword;

    public IndexType getIndexType() {
        return indexType;
    }

    public void setIndexType(IndexType indexType) {
        this.indexType = indexType;
    }

    private IndexType indexType;

    SnowflakeIndexTypeEnum(String name, String keyword) {
        this.name = name;
        this.keyword = keyword;
        this.indexType = new IndexType(name);
    }


    public static SnowflakeIndexTypeEnum getByType(String type) {
        for (SnowflakeIndexTypeEnum value : SnowflakeIndexTypeEnum.values()) {
            if (value.name.equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }

    public String buildIndexScript(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();

        script.append(keyword).append(" ");

        script.append(buildIndexName(tableIndex)).append(" ");

        script.append(buildIndexColumn(tableIndex)).append(" ");

        script.append(buildIndexComment(tableIndex)).append(" ");

        return script.toString();
    }

    private String buildIndexComment(TableIndex tableIndex) {
        if(StringUtils.isBlank(tableIndex.getComment())){
            return "";
        }else {
            return StringUtils.join(SQL_COMMENT,SnowflakeIdentifierProcessor.INSTANCE.escapeString(tableIndex.getComment()),"'");
        }

    }

    private String buildIndexColumn(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("(");
        boolean appended = false;
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            if(StringUtils.isNotBlank(column.getColumnName())) {
                script.append(SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getColumnName()));
                if (!StringUtils.isBlank(column.getAscOrDesc()) && !PRIMARY_KEY.equals(this)) {
                    script.append(" ").append(SnowflakeSqlGuards.requireAscOrDesc(column.getAscOrDesc()));
                }
                script.append(",");
                appended = true;
            }
        }
        if (!appended) {
            throw new IllegalArgumentException("Snowflake index requires at least one named column");
        }
        script.deleteCharAt(script.length() - 1);
        script.append(")");
        return script.toString();
    }

    private String buildIndexName(TableIndex tableIndex) {
        if(this.equals(PRIMARY_KEY)){
            return "";
        }else {
            return SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getName());
        }
    }

    public String buildModifyIndex(TableIndex tableIndex) {
        if (EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return buildDropIndex(tableIndex);
        }
        if (EditStatusEnum.MODIFY.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(buildDropIndex(tableIndex),",\n", "ADD ", buildIndexScript(tableIndex));
        }
        if (EditStatusEnum.ADD.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join("ADD ", buildIndexScript(tableIndex));
        }
        return "";
    }

    private String buildDropIndex(TableIndex tableIndex) {
        if (SnowflakeIndexTypeEnum.PRIMARY_KEY.getName().equals(tableIndex.getType())) {
            return StringUtils.join(SQL_DROP_PRIMARY_KEY);
        }
        return StringUtils.join("DROP INDEX ",
                SnowflakeIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getOldName()));
    }
    public static List<IndexType> getIndexTypes() {
        return Arrays.asList(SnowflakeIndexTypeEnum.values()).stream().map(SnowflakeIndexTypeEnum::getIndexType).collect(java.util.stream.Collectors.toList());
    }
}
