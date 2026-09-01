package ai.chat2db.plugin.mysql.enums.type;

import ai.chat2db.plugin.mysql.MysqlSqlGuards;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_COMMENT_SPACE_SINGLE_QUOTE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DROP_PRIMARY_KEY;

@Getter
public enum MysqlIndexTypeEnum {

    PRIMARY_KEY("Primary", "PRIMARY KEY"),

    NORMAL("Normal", "INDEX"),

    UNIQUE("Unique", "UNIQUE INDEX"),

    FULLTEXT("Fulltext", "FULLTEXT INDEX"),

    SPATIAL("Spatial", "SPATIAL INDEX");


    private String name;


    private String keyword;

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

        script.append(keyword);

        String indexName = buildIndexName(tableIndex);
        if (StringUtils.isNotBlank(indexName)) {
            script.append(" ").append(indexName);
        }

        script.append(" ").append(buildIndexColumn(tableIndex));

        String indexMethod = buildIndexMethod(tableIndex);
        if (StringUtils.isNotBlank(indexMethod)) {
            script.append(" ").append(indexMethod);
        }

        String indexComment = buildIndexComment(tableIndex);
        if (StringUtils.isNotBlank(indexComment)) {
            script.append(" ").append(indexComment);
        }

        return script.toString();
    }

    private String buildIndexMethod(TableIndex tableIndex) {
        if (!PRIMARY_KEY.equals(this) && !NORMAL.equals(this) && !UNIQUE.equals(this)) {
            return StringUtils.EMPTY;
        }
        String method = tableIndex.getMethod();
        if (!"BTREE".equalsIgnoreCase(method) && !"HASH".equalsIgnoreCase(method)) {
            return StringUtils.EMPTY;
        }
        return "USING " + method.toUpperCase(Locale.ROOT);
    }

    private String buildIndexComment(TableIndex tableIndex) {
        if(StringUtils.isBlank(tableIndex.getComment())){
            return "";
        }else {
            return StringUtils.join(SQL_COMMENT_SPACE_SINGLE_QUOTE, MysqlIdentifierProcessor.INSTANCE.escapeString(tableIndex.getComment()),"'");
        }

    }

    private String buildIndexColumn(TableIndex tableIndex) {
        StringBuilder script = new StringBuilder();
        script.append("(");
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            boolean hasColumnName = StringUtils.isNotBlank(column.getColumnName());
            boolean hasExpression = StringUtils.isNotBlank(column.getExpression());
            if (hasColumnName && hasExpression) {
                throw new IllegalArgumentException("MySQL index key part cannot be both column and expression");
            }
            if(hasColumnName) {
                script.append(MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getColumnName()));
                appendPrefixLength(script, column);
                if (!StringUtils.isBlank(column.getAscOrDesc()) && !PRIMARY_KEY.equals(this)) {
                    script.append(" ").append(MysqlSqlGuards.requireAscOrDesc(column.getAscOrDesc()));
                }
                script.append(",");
            } else if (hasExpression) {
                requireFunctionalIndexSupport();
                script.append("(").append(MysqlSqlGuards.requireFunctionalIndexExpression(column.getExpression())).append(")");
                if (!StringUtils.isBlank(column.getAscOrDesc())) {
                    script.append(" ").append(MysqlSqlGuards.requireAscOrDesc(column.getAscOrDesc()));
                }
                script.append(",");
            }
        }
        if (script.length() == 1) {
            throw new IllegalArgumentException("MySQL index must include at least one column or expression");
        }
        script.deleteCharAt(script.length() - 1);
        script.append(")");
        return script.toString();
    }

    private void appendPrefixLength(StringBuilder script, TableIndexColumn column) {
        Long subPart = column.getSubPart();
        if (subPart == null || subPart <= 0) {
            return;
        }
        script.append("(").append(subPart).append(")");
    }

    private void requireFunctionalIndexSupport() {
        if (PRIMARY_KEY.equals(this) || FULLTEXT.equals(this) || SPATIAL.equals(this)) {
            throw new IllegalArgumentException("MySQL functional indexes only support normal or unique indexes");
        }
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            return;
        }
        String dbVersion = Chat2DBContext.getDbVersion();
        if (!MysqlSqlGuards.supportsFunctionalIndex(dbVersion)) {
            throw new IllegalArgumentException("MySQL functional indexes require MySQL 8.0.13 or later: " + dbVersion);
        }
    }

    private String buildIndexName(TableIndex tableIndex) {
        if(this.equals(PRIMARY_KEY)){
            return "";
        }else {
            return MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getName());
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
        if (MysqlIndexTypeEnum.PRIMARY_KEY.getName().equals(tableIndex.getType())) {
            return StringUtils.join(SQL_DROP_PRIMARY_KEY);
        }
        return StringUtils.join("DROP INDEX ", MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableIndex.getOldName()));
    }
    public static List<IndexType> getIndexTypes() {
        return Arrays.asList(MysqlIndexTypeEnum.values()).stream().map(MysqlIndexTypeEnum::getIndexType).collect(java.util.stream.Collectors.toList());
    }
}
