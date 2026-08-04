package ai.chat2db.plugin.oscar.enums.type;

import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.plugin.oscar.OscarSqlGuards;
import ai.chat2db.plugin.oscar.util.OscarUtils;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.IndexType;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import lombok.Getter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

@Getter
public enum OscarIndexTypeEnum {

    PRIMARY_KEY("Primary"),
    NORMAL("Normal"),
    UNIQUE("Unique");

    private final String name;
    private final IndexType indexType;

    OscarIndexTypeEnum(String name) {
        this.name = name;
        this.indexType = new IndexType(name);
    }

    public static OscarIndexTypeEnum getByType(String type) {
        for (OscarIndexTypeEnum value : OscarIndexTypeEnum.values()) {
            if (value.name.equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }

    public String buildIndexScript(TableIndex tableIndex) {
        if (PRIMARY_KEY.equals(this)) {
            return SQLConstants.ALTER_TABLE_SQL_PREFIX + tableName(tableIndex)
                    + SQLConstants.CONSTRAINT_ADD_SQL + OscarUtils.quoteIdentifierIgnoreCase(tableIndex.getName())
                    + SQLConstants.PRIMARY_KEY_SQL + buildIndexColumn(tableIndex, false);
        }
        StringBuilder script = new StringBuilder();
        if (UNIQUE.equals(this)) {
            script.append(SQLConstants.CREATE_UNIQUE_INDEX_SQL_PREFIX);
        } else {
            script.append(SQLConstants.CREATE_INDEX_SQL_PREFIX);
        }
        script.append(indexName(tableIndex))
                .append(SQLConstants.SQL_ON)
                .append(tableName(tableIndex))
                .append(SQLConstants.SPACE)
                .append(buildIndexColumn(tableIndex, true));
        return script.toString();
    }

    public String buildModifyIndex(TableIndex tableIndex) {
        if (EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return buildDropIndex(tableIndex);
        }
        if (EditStatusEnum.MODIFY.name().equals(tableIndex.getEditStatus())) {
            return StringUtils.join(buildDropIndex(tableIndex), SQLConstants.SEMICOLON_LINE_SEPARATOR,
                    buildIndexScript(tableIndex));
        }
        if (EditStatusEnum.ADD.name().equals(tableIndex.getEditStatus())) {
            return buildIndexScript(tableIndex);
        }
        return SQLConstants.EMPTY;
    }

    private String buildDropIndex(TableIndex tableIndex) {
        if (PRIMARY_KEY.getName().equals(tableIndex.getType())) {
            return SQLConstants.ALTER_TABLE_SQL_PREFIX + tableName(tableIndex) + SQLConstants.DROP_PRIMARY_KEY_SQL;
        }
        return SQLConstants.DROP_INDEX_SQL_PREFIX + indexName(tableIndex);
    }

    private String buildIndexColumn(TableIndex tableIndex, boolean includeSort) {
        if (CollectionUtils.isEmpty(tableIndex.getColumnList())) {
            throw new IllegalArgumentException("Oscar index requires at least one named column");
        }
        StringBuilder script = new StringBuilder(SQLConstants.OPEN_PARENTHESIS);
        int columnCount = 0;
        for (TableIndexColumn column : tableIndex.getColumnList()) {
            if (column == null || StringUtils.isBlank(column.getColumnName())) {
                continue;
            }
            script.append(OscarUtils.quoteIdentifierIgnoreCase(column.getColumnName()));
            if (includeSort && StringUtils.isNotBlank(column.getAscOrDesc())) {
                script.append(SQLConstants.SPACE).append(OscarSqlGuards.requireSortOrder(column.getAscOrDesc()));
            }
            script.append(SQLConstants.COMMA);
            columnCount++;
        }
        if (columnCount == 0) {
            throw new IllegalArgumentException("Oscar index requires at least one named column");
        }
        script.deleteCharAt(script.length() - 1);
        script.append(SQLConstants.CLOSE_PARENTHESIS);
        return script.toString();
    }

    private String tableName(TableIndex tableIndex) {
        return OscarUtils.qualifiedName(tableIndex.getSchemaName(), tableIndex.getTableName());
    }

    private String indexName(TableIndex tableIndex) {
        return OscarUtils.qualifiedName(tableIndex.getSchemaName(), tableIndex.getName());
    }

    public static List<IndexType> getIndexTypes() {
        return Arrays.stream(OscarIndexTypeEnum.values())
                .map(OscarIndexTypeEnum::getIndexType)
                .toList();
    }
}
