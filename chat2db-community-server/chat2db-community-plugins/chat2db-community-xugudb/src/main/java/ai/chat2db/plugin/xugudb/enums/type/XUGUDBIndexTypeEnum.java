package ai.chat2db.plugin.xugudb.enums.type;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.xugudb.identifier.XugudbIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

import static ai.chat2db.plugin.xugudb.constant.XUGUDBIndexTypeEnumConstants.*;
public enum XUGUDBIndexTypeEnum {

    PRIMARY_KEY("Primary", "PRIMARY KEY"),

    NORMAL("Normal", "INDEX"),

    UNIQUE("Unique", "UNIQUE INDEX"),

    BTREE("BTREE", "btree");










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

    XUGUDBIndexTypeEnum(String name, String keyword) {
        this.name = name;
        this.keyword = keyword;
        this.indexType = new IndexType(name);
    }


    public static XUGUDBIndexTypeEnum getByType(String type) {
        for (XUGUDBIndexTypeEnum value : XUGUDBIndexTypeEnum.values()) {
            if (value.name.equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }

    public String buildIndexScript(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        if (PRIMARY_KEY.equals(this)) {
            script.append(SQL_ALTER_TABLE_2).append(XugudbIdentifierProcessor.escapeIdentifier(tableIndex.getSchemaName())).append("\".\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableIndex.getTableName())).append("\" ADD PRIMARY KEY ").append(buildIndexColumn(tableIndex));
        } else {
            if (UNIQUE.equals(this)) {
                script.append(SQL_CREATE_UNIQUE_INDEX);
            } else {
                script.append(SQL_CREATE_INDEX);
            }
            script.append(buildIndexName(tableIndex)).append(SQL_ON).append(XugudbIdentifierProcessor.escapeIdentifier(tableIndex.getSchemaName())).append("\".\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableIndex.getTableName())).append("\" ").append(buildIndexColumn(tableIndex));
        }
        return script.toString();
    }


    private String buildIndexColumn(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("(");
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            if (StringUtils.isNotBlank(column.getColumnName())) {
                script.append("\"").append(XugudbIdentifierProcessor.escapeIdentifier(column.getColumnName())).append("\"");
                if (!StringUtils.isBlank(column.getAscOrDesc()) && !PRIMARY_KEY.equals(this)) {
                    script.append(" ").append(validateAscOrDesc(column.getAscOrDesc()));
                }
                script.append(",");
            }
        }
        script.deleteCharAt(script.length() - 1);
        script.append(")");
        return script.toString();
    }

    private String buildIndexName(TableIndex tableIndex) {
        return "\"" + XugudbIdentifierProcessor.escapeIdentifier(tableIndex.getSchemaName()) + "\"." + "\"" + XugudbIdentifierProcessor.escapeIdentifier(tableIndex.getName()) + "\"";
    }

    private static String validateAscOrDesc(String ascOrDesc) {
        String trimmed = ascOrDesc.trim();
        if ("ASC".equalsIgnoreCase(trimmed)) {
            return "ASC";
        }
        if ("DESC".equalsIgnoreCase(trimmed)) {
            return "DESC";
        }
        throw new IllegalArgumentException("Unsupported index sort order: " + ascOrDesc);
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
        if (PRIMARY_KEY.equals(this)) {
            String tableName = "\"" + XugudbIdentifierProcessor.escapeIdentifier(tableIndex.getSchemaName()) + "\"." + "\"" + XugudbIdentifierProcessor.escapeIdentifier(tableIndex.getTableName()) + "\"";
            return StringUtils.join(SQL_ALTER_TABLE,tableName,SQL_DROP_PRIMARY_KEY);
        }
        StringBuilder script = new StringBuilder();
        script.append(SQL_DROP_INDEX);
        script.append(buildIndexName(tableIndex));

        return script.toString();
    }

    public static List<IndexType> getIndexTypes() {
        return Arrays.asList(XUGUDBIndexTypeEnum.values()).stream().map(XUGUDBIndexTypeEnum::getIndexType).collect(java.util.stream.Collectors.toList());
    }
}
