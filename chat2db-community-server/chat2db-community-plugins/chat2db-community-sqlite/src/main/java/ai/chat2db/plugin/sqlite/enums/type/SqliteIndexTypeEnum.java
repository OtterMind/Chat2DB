package ai.chat2db.plugin.sqlite.enums.type;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.sqlite.identifier.SqliteIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

import static ai.chat2db.plugin.sqlite.constant.SqliteIndexTypeEnumConstants.*;
public enum SqliteIndexTypeEnum {

    PRIMARY_KEY("Primary", "PRIMARY KEY"),

    NORMAL("Normal", "INDEX"),

    UNIQUE("Unique", "UNIQUE INDEX");








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

    SqliteIndexTypeEnum(String name, String keyword) {
        this.name = name;
        this.keyword = keyword;
        this.indexType = new IndexType(name);
    }


    public static SqliteIndexTypeEnum getByType(String type) {
        for (SqliteIndexTypeEnum value : SqliteIndexTypeEnum.values()) {
            if (value.name.equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }

    public String buildIndexScript(TableIndex tableIndex) {
        if (this.equals(PRIMARY_KEY)) {
            return buildPrimaryKeyScript(tableIndex);
        } else {
            StringBuilder script = new StringBuilder();

            script.append(keyword).append(" ");

            script.append(buildIndexName(tableIndex)).append(SQL_ON).append(quoteName(tableIndex.getTableName())).append(" ");

            script.append(buildIndexColumn(tableIndex)).append(" ");
            return script.toString();
        }


    }

    private String buildPrimaryKeyScript(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("CONSTRAINT ").append(buildIndexName(tableIndex)).append(" ").append(keyword).append(" ").append(buildIndexColumn(tableIndex));
        return script.toString();
    }

    private String buildIndexComment(TableIndex tableIndex) {
        if (StringUtils.isBlank(tableIndex.getComment())) {
            return "";
        } else {
            return StringUtils.join(SQL_COMMENT, tableIndex.getComment(), "'");
        }

    }

    private String buildIndexColumn(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("(");
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            if (StringUtils.isNotBlank(column.getColumnName())) {
                script.append(quoteName(column.getColumnName())).append(",");
            }
        }
        if (script.length() == 1) {
            throw new IllegalArgumentException("SQLite index must contain at least one named column");
        }
        script.deleteCharAt(script.length() - 1);
        script.append(")");
        return script.toString();
    }

    private String buildIndexName(TableIndex tableIndex) {
        if (this.equals(PRIMARY_KEY)) {
            return quoteName(tableIndex.getTableName() + "_pk");
        } else {
            return quoteName(tableIndex.getName());
        }
    }

    private static String quoteName(String name) {
        return SqliteIdentifierProcessor.INSTANCE.quoteIdentifierAlways(name);
    }

    public String buildModifyIndex(TableIndex tableIndex) {
        if (EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return buildDropIndex(tableIndex);
        }
        if (EditStatusEnum.MODIFY.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(buildDropIndex(tableIndex), ",\n", "ADD ", buildIndexScript(tableIndex));
        }
        if (EditStatusEnum.ADD.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(SQL_CREATE, buildIndexScript(tableIndex));
        }
        return "";
    }

    private String buildDropIndex(TableIndex tableIndex) {
        if (SqliteIndexTypeEnum.PRIMARY_KEY.getName().equals(tableIndex.getType())) {
            return StringUtils.join(SQL_DROP_PRIMARY_KEY);
        }
        return StringUtils.join(SQL_DROP_INDEX, quoteName(tableIndex.getOldName()));
    }

    public static List<IndexType> getIndexTypes() {
        return Arrays.asList(SqliteIndexTypeEnum.values()).stream().map(SqliteIndexTypeEnum::getIndexType).collect(java.util.stream.Collectors.toList());
    }
}
