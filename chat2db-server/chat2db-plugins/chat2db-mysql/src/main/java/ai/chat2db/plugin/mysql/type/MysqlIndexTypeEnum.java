package ai.chat2db.plugin.mysql.type;

import ai.chat2db.plugin.mysql.util.MysqlSqlUtils;
import ai.chat2db.spi.enums.EditStatus;
import ai.chat2db.spi.model.IndexType;
import ai.chat2db.spi.model.TableIndex;
import ai.chat2db.spi.model.TableIndexColumn;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

public enum MysqlIndexTypeEnum {

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

    MysqlIndexTypeEnum(String name, String keyword) {
        this.name = name;
        this.keyword = keyword;
        this.indexType = new IndexType(name);
    }


    public static MysqlIndexTypeEnum getByType(String type) {
        for (MysqlIndexTypeEnum value : MysqlIndexTypeEnum.values()) {
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
            return StringUtils.join("COMMENT ", MysqlSqlUtils.quoteString(tableIndex.getComment()));
        }

    }

    private String buildIndexColumn(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("(");
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            if(StringUtils.isNotBlank(column.getColumnName())) {
                script.append("`").append(column.getColumnName()).append("`");
                if (!StringUtils.isBlank(column.getAscOrDesc()) && !PRIMARY_KEY.equals(this)) {
                    script.append(" ").append(column.getAscOrDesc());
                }
                script.append(",");
            }
        }
        script.deleteCharAt(script.length() - 1);
        script.append(")");
        return script.toString();
    }

    private String buildIndexName(TableIndex tableIndex) {
        if(this.equals(PRIMARY_KEY)){
            return "";
        }else {
            return "`"+tableIndex.getName()+"`";
        }
    }

    public String buildModifyIndex(TableIndex tableIndex) {
        if (EditStatus.DELETE.name().equals(tableIndex.getEditStatus())) {
            return buildDropIndex(tableIndex);
        }
        if (EditStatus.MODIFY.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(buildDropIndex(tableIndex),",\n", "ADD ", buildIndexScript(tableIndex));
        }
        if (EditStatus.ADD.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join("ADD ", buildIndexScript(tableIndex));
        }
        return "";
    }

    private String buildDropIndex(TableIndex tableIndex) {
        if (MysqlIndexTypeEnum.PRIMARY_KEY.getName().equals(tableIndex.getType())) {
            return StringUtils.join("DROP PRIMARY KEY");
        }
        return StringUtils.join("DROP INDEX `", tableIndex.getOldName(),"`");
    }
    public static List<IndexType> getIndexTypes() {
        return Arrays.asList(MysqlIndexTypeEnum.values()).stream().map(MysqlIndexTypeEnum::getIndexType).collect(java.util.stream.Collectors.toList());
    }
}
