package ai.chat2db.plugin.oracle.enums.type;

import ai.chat2db.plugin.oracle.OracleSqlGuards;
import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

import static ai.chat2db.plugin.oracle.constant.OracleIndexTypeEnumConstants.*;
public enum OracleIndexTypeEnum {

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

    OracleIndexTypeEnum(String name, String keyword) {
        this.name = name;
        this.keyword = keyword;
        this.indexType = new IndexType(name);
    }


    public static OracleIndexTypeEnum getByType(String type) {
        for (OracleIndexTypeEnum value : OracleIndexTypeEnum.values()) {
            if (value.name.equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }

    public String buildIndexScript(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        if (PRIMARY_KEY.equals(this)) {
            script.append(SQL_ALTER_TABLE).append(qualifiedName(tableIndex.getSchemaName(), tableIndex.getTableName()))
                    .append(" ADD CONSTRAINT ")
                    .append(OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getTableName() + "_pk"))
                    .append(" PRIMARY KEY ").append(buildIndexColumn(tableIndex));
        } else {
            if (UNIQUE.equals(this)) {
                script.append(SQL_CREATE_UNIQUE_INDEX);
            } else {
                script.append(SQL_CREATE_INDEX);
            }
            script.append(buildIndexName(tableIndex)).append(" ON ")
                    .append(qualifiedName(tableIndex.getSchemaName(), tableIndex.getTableName()))
                    .append(" ").append(buildIndexColumn(tableIndex));
        }
        return script.toString();
    }


    private String buildIndexColumn(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("(");
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            if (StringUtils.isNotBlank(column.getColumnName())) {
                script.append(OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getColumnName()));
                if (!StringUtils.isBlank(column.getAscOrDesc()) && !PRIMARY_KEY.equals(this)) {
                    script.append(" ").append(OracleSqlGuards.requireAscOrDesc(column.getAscOrDesc()));
                }
                script.append(",");
            }
        }
        script.deleteCharAt(script.length() - 1);
        script.append(")");
        return script.toString();
    }

    private String buildIndexName(TableIndex tableIndex) {
        return qualifiedName(tableIndex.getSchemaName(), tableIndex.getName());
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
        if (OracleIndexTypeEnum.PRIMARY_KEY.getName().equals(tableIndex.getType())) {
            String tableName = qualifiedName(tableIndex.getSchemaName(), tableIndex.getTableName());
            return StringUtils.join(SQL_ALTER_TABLE, tableName, SQL_DROP_PRIMARY_KEY);
        }
        StringBuilder script = new StringBuilder();
        script.append(SQL_DROP_INDEX);
        script.append(buildIndexName(tableIndex));

        return script.toString();
    }

    private static String qualifiedName(String schemaName, String objectName) {
        String quotedObject = OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(objectName);
        if (StringUtils.isBlank(schemaName)) {
            return quotedObject;
        }
        return OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName) + "." + quotedObject;
    }

    public static List<IndexType> getIndexTypes() {
        return Arrays.asList(OracleIndexTypeEnum.values()).stream().map(OracleIndexTypeEnum::getIndexType).collect(java.util.stream.Collectors.toList());
    }
}
