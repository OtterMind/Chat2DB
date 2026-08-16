package ai.chat2db.plugin.sqlserver.enums.type;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.sqlserver.identifier.SqlServerIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

import static ai.chat2db.plugin.sqlserver.constant.SqlServerIndexTypeEnumConstants.*;
public enum SqlServerIndexTypeEnum {

    PRIMARY_KEY("Primary", "PRIMARY KEY"),


    UNIQUE_CLUSTERED("UNIQUE CLUSTERED", "UNIQUE CLUSTERED INDEX"),

    CLUSTERED("CLUSTERED", "CLUSTERED INDEX"),


    NONCLUSTERED("NONCLUSTERED", "NONCLUSTERED INDEX"),

    UNIQUE_NONCLUSTERED("UNIQUE NONCLUSTERED", "UNIQUE NONCLUSTERED INDEX"),

    SPATIAL("SPATIAL", "SPATIAL INDEX"),

    XML("XML", "XML INDEX");






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

    SqlServerIndexTypeEnum(String name, String keyword) {
        this.name = name;
        this.keyword = keyword;
        this.indexType = new IndexType(name);
    }


    public static SqlServerIndexTypeEnum getByType(String type) {
        for (SqlServerIndexTypeEnum value : SqlServerIndexTypeEnum.values()) {
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
                    .append(qualifiedTableName(tableIndex)).append(" ADD CONSTRAINT ")
                    .append(SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getTableName() + "_pk"))
                    .append(" ").append(keyword).append(" ").append(buildIndexColumn(tableIndex));
        } else {
            script.append(SQL_CREATE).append(keyword).append(" ");
            script.append(buildIndexName(tableIndex)).append("\n ON ")
                    .append(qualifiedTableName(tableIndex)).append(" ").append(buildIndexColumn(tableIndex));
        }
        script.append("\ngo");
        return script.toString();
    }


    private String buildIndexColumn(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("(");
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            if (StringUtils.isNotBlank(column.getColumnName())) {
                script.append(SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getColumnName()));
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

    private static String validateAscOrDesc(String ascOrDesc) {
        if (!"ASC".equalsIgnoreCase(ascOrDesc) && !"DESC".equalsIgnoreCase(ascOrDesc)) {
            throw new IllegalArgumentException("Invalid SQL Server index column sort order: " + ascOrDesc);
        }
        return ascOrDesc;
    }

    private String buildIndexName(TableIndex tableIndex) {
        return SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getName());
    }

    public String buildModifyIndex(TableIndex tableIndex) {
        if (EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return buildDropIndex(tableIndex);
        }
        if (EditStatusEnum.MODIFY.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(buildDropIndex(tableIndex), "\n", buildIndexScript(tableIndex));
        }
        if (EditStatusEnum.ADD.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(buildIndexScript(tableIndex));
        }
        return "";
    }

    private String buildDropIndex(TableIndex tableIndex) {
        if (SqlServerIndexTypeEnum.PRIMARY_KEY.getName().equals(tableIndex.getType())) {
            return StringUtils.join(SQL_ALTER_TABLE, qualifiedTableName(tableIndex),
                    " DROP CONSTRAINT ", buildIndexName(tableIndex), "\ngo");
        }
        StringBuilder script = new StringBuilder();
        script.append(SQL_DROP_INDEX);
        script.append(buildIndexName(tableIndex));
        script.append(SQL_ON).append(qualifiedTableName(tableIndex)).append(" \ngo");

        return script.toString();
    }

    private static String qualifiedTableName(TableIndex tableIndex) {
        StringBuilder name = new StringBuilder();
        if (StringUtils.isNotBlank(tableIndex.getSchemaName())) {
            name.append(SqlServerIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getSchemaName()))
                    .append('.');
        }
        return name.append(SqlServerIdentifierProcessor.INSTANCE
                .quoteIdentifierAlways(tableIndex.getTableName())).toString();
    }

    public static List<IndexType> getIndexTypes() {
        return Arrays.asList(SqlServerIndexTypeEnum.values()).stream().map(SqlServerIndexTypeEnum::getIndexType).collect(java.util.stream.Collectors.toList());
    }
}
