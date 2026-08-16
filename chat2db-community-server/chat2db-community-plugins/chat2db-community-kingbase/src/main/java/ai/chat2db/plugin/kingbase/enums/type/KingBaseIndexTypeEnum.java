package ai.chat2db.plugin.kingbase.enums.type;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.kingbase.identifier.KingBaseSQLIdentifierProcessor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

import static ai.chat2db.plugin.kingbase.constant.KingBaseIndexTypeEnumConstants.*;
public enum KingBaseIndexTypeEnum {

    PRIMARY("Primary", "PRIMARY KEY"),

    FOREIGN("Foreign", "FOREIGN KEY"),

    NORMAL("Normal", "INDEX"),

    UNIQUE("Unique", "UNIQUE"),
    ;








    private String name;
    private String keyword;

    private IndexType indexType;


    KingBaseIndexTypeEnum(String name, String keyword) {
        this.name = name;
        this.keyword = keyword;
        this.indexType =new IndexType(name);
    }

    public static KingBaseIndexTypeEnum getByType(String type) {
        for (KingBaseIndexTypeEnum value : KingBaseIndexTypeEnum.values()) {
            if (value.name.equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }

    public static List<IndexType> getIndexTypes() {
        return Arrays.asList(KingBaseIndexTypeEnum.values()).stream().map(KingBaseIndexTypeEnum::getIndexType).collect(java.util.stream.Collectors.toList());
    }

    public IndexType getIndexType() {
        return indexType;
    }

    public String getName() {
        return name;
    }

    public String getKeyword() {
        return keyword;
    }

    public String buildIndexScript(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        if (NORMAL.equals(this)) {
            script.append(SQL_CREATE).append(" ");
            script.append(buildIndexUnique(tableIndex)).append(" ");
            script.append(buildIndexConcurrently(tableIndex)).append(" ");
            script.append(buildIndexName(tableIndex)).append(" ");
            script.append(SQL_ON).append(qualifiedName(tableIndex.getSchemaName(), tableIndex.getTableName())).append(" ");
            script.append(buildIndexMethod(tableIndex)).append(" ");
            script.append(buildIndexColumn(tableIndex));
        } else {
            script.append("CONSTRAINT").append(" ");
            script.append(buildIndexName(tableIndex)).append(" ");
            script.append(keyword).append(" ");
            script.append(buildIndexColumn(tableIndex));
            script.append(buildForeignColum(tableIndex));
        }
        return script.toString();
    }

    private String buildForeignColum(TableIndex tableIndex) {
        if (FOREIGN.equals(this)) {
            StringBuilder script = new StringBuilder();
            script.append(" REFERENCES ");
            if (StringUtils.isNotBlank(tableIndex.getForeignSchemaName())) {
                script.append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getForeignSchemaName())).append(".");
            }
            if (StringUtils.isNotBlank(tableIndex.getForeignTableName())) {
                script.append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getForeignTableName())).append(" ");
            }
            if (CollectionUtils.isNotEmpty(tableIndex.getForeignColumnNamelist())) {
                script.append("(");
                for (String column : tableIndex.getForeignColumnNamelist()) {
                    if (StringUtils.isNotBlank(column)) {
                        script.append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column)).append(",");
                    }
                }
                script.deleteCharAt(script.length() - 1);
                script.append(")");
            }
            return script.toString();
        }
        return "";
    }

    private String buildIndexMethod(TableIndex tableIndex) {
        if (StringUtils.isNotBlank(tableIndex.getMethod())) {
            return "USING " + KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getMethod());
        } else {
            return "";
        }
    }

    private String buildIndexConcurrently(TableIndex tableIndex) {
        if (BooleanUtils.isTrue(tableIndex.getConcurrently())) {
            return "CONCURRENTLY";
        } else {
            return "";
        }
    }

    private String buildIndexUnique(TableIndex tableIndex) {
        if (BooleanUtils.isTrue(tableIndex.getUnique())) {
            return "UNIQUE " + keyword;
        } else {
            return keyword;
        }
    }

    public String buildIndexComment(TableIndex tableIndex) {
        if (StringUtils.isBlank(tableIndex.getComment()) || EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return "";
        } else if (NORMAL.equals(this)) {
            return StringUtils.join(SQL_COMMENT_INDEX, " ",
                    qualifiedName(tableIndex.getSchemaName(), tableIndex.getName()), " IS '", KingBaseSQLIdentifierProcessor.INSTANCE.escapeString(tableIndex.getComment()), "';");
        } else {
            return StringUtils.join(SQL_COMMENT_CONSTRAINT, " ",
                    KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getName()), " ON ",
                    qualifiedName(tableIndex.getSchemaName(), tableIndex.getTableName()), " IS '",
                    KingBaseSQLIdentifierProcessor.INSTANCE.escapeString(tableIndex.getComment()), "';");
        }
    }

    private String buildIndexColumn(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("(");
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            if (StringUtils.isNotBlank(column.getColumnName())) {
                script.append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getColumnName())).append(",");
            }
        }
        script.deleteCharAt(script.length() - 1);
        script.append(")");
        return script.toString();
    }

    private String buildIndexName(TableIndex tableIndex) {
        return KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getName());
    }

    public String buildModifyIndex(TableIndex tableIndex) {
        boolean isNormal = NORMAL.equals(this);
        if (EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return buildDropIndex(tableIndex);
        }
        if (EditStatusEnum.MODIFY.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(buildDropIndex(tableIndex), isNormal ? ";\n" : ",\n\tADD ", buildIndexScript(tableIndex));
        }
        if (EditStatusEnum.ADD.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(isNormal ? "" : "ADD ", buildIndexScript(tableIndex));
        }
        return "";
    }

    private String buildDropIndex(TableIndex tableIndex) {
        if (NORMAL.equals(this)) {
            return StringUtils.join(SQL_DROP_INDEX,
                    qualifiedName(tableIndex.getSchemaName(), tableIndex.getOldName()));
        }
        return StringUtils.join(SQL_DROP_CONSTRAINT, KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getOldName()));
    }

    private static String qualifiedName(String schemaName, String objectName) {
        String quotedName = KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(objectName);
        if (StringUtils.isBlank(schemaName)) {
            return quotedName;
        }
        return KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName) + "." + quotedName;
    }
}
