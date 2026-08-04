package ai.chat2db.plugin.db2.enums.type;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.db2.Db2SqlGuards;
import ai.chat2db.plugin.db2.identifier.Db2IdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

import static ai.chat2db.plugin.db2.constant.DB2IndexTypeEnumConstants.*;
public enum DB2IndexTypeEnum {

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

    DB2IndexTypeEnum(String name, String keyword) {
        this.name = name;
        this.keyword = keyword;
        this.indexType = new IndexType(name);
    }


    public static DB2IndexTypeEnum getByType(String type) {
        for (DB2IndexTypeEnum value : DB2IndexTypeEnum.values()) {
            if (value.name.equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }

    public String buildIndexScript(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        if (PRIMARY_KEY.equals(this)) {
            script.append(SQL_ALTER_TABLE_2).append(Db2IdentifierProcessor.escapeIdentifier(tableIndex.getSchemaName())).append("\".\"").append(Db2IdentifierProcessor.escapeIdentifier(tableIndex.getTableName())).append("\" ADD PRIMARY KEY ").append(buildIndexColumn(tableIndex));
        } else {
            if (UNIQUE.equals(this)) {
                script.append(SQL_CREATE_UNIQUE_INDEX);
            } else {
                script.append(SQL_CREATE_INDEX);
            }
            script.append(buildIndexName(tableIndex)).append(SQL_ON).append(Db2IdentifierProcessor.escapeIdentifier(tableIndex.getSchemaName())).append("\".\"").append(Db2IdentifierProcessor.escapeIdentifier(tableIndex.getTableName())).append("\" ").append(buildIndexColumn(tableIndex));
        }

        return script.toString();
    }


    private String buildIndexColumn(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("(");
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            if (StringUtils.isNotBlank(column.getColumnName())) {
                script.append("\"").append(Db2IdentifierProcessor.escapeIdentifier(column.getColumnName())).append("\"");
                if (!StringUtils.isBlank(column.getAscOrDesc()) && !PRIMARY_KEY.equals(this)) {
                    script.append(" ").append(Db2SqlGuards.requireSortDirection(column.getAscOrDesc()));
                }
                script.append(",");
            }
        }
        script.deleteCharAt(script.length() - 1);
        script.append(")");
        return script.toString();
    }

    public String buildIndexComment(TableIndex tableIndex) {
        if (StringUtils.isBlank(tableIndex.getComment()) || EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return "";
        } else if (NORMAL.equals(this) || UNIQUE.equals(this)) {
            return StringUtils.join(SQL_COMMENT_INDEX, " ",
                    "\"", Db2IdentifierProcessor.escapeIdentifier(tableIndex.getName()), "\" IS '", Db2IdentifierProcessor.INSTANCE.escapeString(tableIndex.getComment()), "';");
        } else {
            return StringUtils.join(SQL_COMMENT_CONSTRAINT, " \"", Db2IdentifierProcessor.escapeIdentifier(tableIndex.getName()), "\" ON \"", Db2IdentifierProcessor.escapeIdentifier(tableIndex.getSchemaName()),
                    "\".\"", Db2IdentifierProcessor.escapeIdentifier(tableIndex.getTableName()), "\" IS '", Db2IdentifierProcessor.INSTANCE.escapeString(tableIndex.getComment()), "';");
        }
    }

    private String buildIndexName(TableIndex tableIndex) {
        return "\"" + Db2IdentifierProcessor.escapeIdentifier(tableIndex.getSchemaName()) + "\"." + "\"" + Db2IdentifierProcessor.escapeIdentifier(tableIndex.getName()) + "\"";
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
        if (DB2IndexTypeEnum.PRIMARY_KEY.getName().equals(tableIndex.getType())) {
            String tableName = "\"" + Db2IdentifierProcessor.escapeIdentifier(tableIndex.getSchemaName()) + "\"." + "\"" + Db2IdentifierProcessor.escapeIdentifier(tableIndex.getTableName()) + "\"";
            return StringUtils.join(SQL_ALTER_TABLE,tableName,SQL_DROP_PRIMARY_KEY);
        }
        StringBuilder script = new StringBuilder();
        script.append(SQL_DROP_INDEX);
        script.append(buildIndexName(tableIndex));

        return script.toString();
    }

    public static List<IndexType> getIndexTypes() {
        return Arrays.asList(DB2IndexTypeEnum.values()).stream().map(DB2IndexTypeEnum::getIndexType).collect(java.util.stream.Collectors.toList());
    }
}
