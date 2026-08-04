package ai.chat2db.plugin.sqlite.enums.type;

import ai.chat2db.plugin.sqlite.SqliteSqlGuards;
import ai.chat2db.plugin.sqlite.identifier.SqliteIdentifierProcessor;
import ai.chat2db.spi.IColumnBuilder;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.util.SqlUtils;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public enum SqliteColumnTypeEnum implements IColumnBuilder {


    INTEGER("INTEGER", true, false, true, false, false, true, false, true, false, false),

    REAL("REAL", true, false, true, false, false, true, false, true, false, false),

    BLOB("BLOB", true, false, true, false, false, true, false, true, false, false),


    TEXT("TEXT", true, false, true, false, false, true, false, true, false, false),

    ;
    private ColumnType columnType;

    public static SqliteColumnTypeEnum getByType(String dataType) {
        return dataType == null ? null : COLUMN_TYPE_MAP.get(SqlUtils.removeDigits(dataType.toUpperCase(Locale.ROOT)));
    }

    public ColumnType getColumnType() {
        return columnType;
    }


    SqliteColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale, boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset, boolean supportCollation, boolean supportComments, boolean supportDefaultValue, boolean supportExtent, boolean supportValue) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable, supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue, supportExtent, supportValue, false);
    }

    private static Map<String, SqliteColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (SqliteColumnTypeEnum value : SqliteColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName(), value);
        }
    }


    @Override
    public String buildCreateColumnSql(TableColumn column) {
        SqliteColumnTypeEnum type = column.getColumnType() == null ? null
                : COLUMN_TYPE_MAP.get(column.getColumnType().toUpperCase(Locale.ROOT));
        if (type == null) {
            return buildDefaultColumn(column, false);
        }
        StringBuilder script = new StringBuilder();

        script.append(SqliteIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildCharset(column, type)).append(" ");

        script.append(buildCollation(column, type)).append(" ");

        script.append(buildNullable(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        script.append(buildExt(column, type)).append(" ");

        script.append(buildAutoIncrement(column, type)).append(" ");

        return script.toString();
    }

    private String buildCharset(TableColumn column, SqliteColumnTypeEnum type) {
        if (!type.getColumnType().isSupportCharset() || StringUtils.isEmpty(column.getCharSetName())) {
            return "";
        }
        return StringUtils.join("CHARACTER SET ", SqliteSqlGuards.requireSafeName(column.getCharSetName(), "charset"));
    }

    private String buildCollation(TableColumn column, SqliteColumnTypeEnum type) {
        if (!type.getColumnType().isSupportCollation() || StringUtils.isEmpty(column.getCollationName())) {
            return "";
        }
        return StringUtils.join("COLLATE ", SqliteSqlGuards.requireSafeName(column.getCollationName(), "collation"));
    }

    @Override
    public String buildModifyColumn(TableColumn tableColumn) {
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            return StringUtils.join("ADD ", buildCreateColumnSql(tableColumn));
        }
        return "";
    }

    private String buildAutoIncrement(TableColumn column, SqliteColumnTypeEnum type) {
        if (!type.getColumnType().isSupportAutoIncrement()) {
            return "";
        }
        if (column.getAutoIncrement() != null && column.getAutoIncrement()) {
            return "AUTO_INCREMENT";
        }
        return "";
    }


    private String buildExt(TableColumn column, SqliteColumnTypeEnum type) {
        if (!type.columnType.isSupportExtent() || StringUtils.isEmpty(column.getExtent())) {
            return "";
        }
        return column.getComment();
    }

    private String buildDefaultValue(TableColumn column, SqliteColumnTypeEnum type) {
        if (!type.getColumnType().isSupportDefaultValue() || StringUtils.isEmpty(column.getDefaultValue())) {
            return "";
        }

        if ("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT ''");
        }

        if ("NULL".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT NULL");
        }

        return StringUtils.join("DEFAULT ", SqliteSqlGuards.escapeColumnDefault(column.getDefaultValue()));
    }

    private String buildNullable(TableColumn column, SqliteColumnTypeEnum type) {
        if (!type.getColumnType().isSupportNullable()) {
            return "";
        }
        if (column.getNullable() != null && 1 == column.getNullable()) {
            return "NULL";
        } else {
            return "NOT NULL";
        }
    }

    private String buildDataType(TableColumn column, SqliteColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();

        if (column.getColumnSize() == null) {
            return columnType;
        }
        if (column.getColumnSize() != null && column.getDecimalDigits() == null) {
            return StringUtils.join(columnType, "(", column.getColumnSize() + ")");
        }
        if (column.getColumnSize() != null && column.getDecimalDigits() != null) {
            return StringUtils.join(columnType, "(", column.getColumnSize() + "," + column.getDecimalDigits() + ")");
        }
        return columnType;
    }


    public static List<ColumnType> getTypes() {
        return Arrays.stream(SqliteColumnTypeEnum.values()).map(columnTypeEnum ->
                columnTypeEnum.getColumnType()
        ).toList();
    }


}
