package ai.chat2db.plugin.clickhouse.enums.type;

import ai.chat2db.plugin.clickhouse.ClickHouseSqlGuards;
import ai.chat2db.plugin.clickhouse.identifier.ClickHouseIdentifierProcessor;
import ai.chat2db.spi.IColumnBuilder;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.chat2db.plugin.clickhouse.constant.ClickHouseColumnTypeEnumConstants.*;
public enum ClickHouseColumnTypeEnum implements IColumnBuilder {

    String("String", false, false, true, false, false, false, true, true, false, false),
    Int8("Int8", false, false, true, false, false, false, true, true, false, false),
    Int16("Int16", false, false, true, false, false, false, true, true, false, false),
    Int32("Int32", false, false, true, false, false, false, true, true, false, false),
    Int64("Int64", false, false, true, false, false, false, true, true, false, false),
    Int128("Int128", false, false, true, false, false, false, true, true, false, false),
    Int256("Int256", false, false, true, false, false, false, true, true, false, false),
    UInt8("UInt8", false, false, true, false, false, false, true, true, false, false),
    UInt16("UInt16", false, false, true, false, false, false, true, true, false, false),
    UInt32("UInt32", false, false, true, false, false, false, true, true, false, false),
    UInt64("UInt64", false, false, true, false, false, false, true, true, false, false),
    UInt128("UInt128", false, false, true, false, false, false, true, true, false, false),
    UInt256("UInt256", false, false, true, false, false, false, true, true, false, false),
    Float32("Float32", false, false, true, false, false, false, true, true, false, false),
    Float64("Float64", false, false, true, false, false, false, true, true, false, false),
    Decimal("Decimal", true, true, true, false, false, false, true, true, false, false),
    Boolean("Boolean", false, false, true, false, false, false, true, true, false, false),
    FixedString("FixedString", false, false, true, false, false, false, true, true, false, false),
    UUID("UUID", false, false, true, false, false, false, true, true, false, false),
    Date("Date", false, false, true, false, false, false, true, true, false, false),
    DATE32("DATE32", false, false, true, false, false, false, true, true, false, false),
    DateTime("DateTime", false, false, true, false, false, false, true, true, false, false),
    DateTime64("DateTime64", false, false, true, false, false, false, true, true, false, false),
    Enum8("Enum8", false, false, true, false, false, false, true, true, false, false),
    Enum16("Enum16", false, false, true, false, false, false, true, true, false, false),
    Array("Array", false, false, false, false, false, false, true, true, false, false),
    JSON("JSON", false, false, true, false, false, false, true, true, false, false),
    Nested("Nested", false, false, true, false, false, false, true, true, false, false),
    Map("Map", true, true, true, false, false, false, true, true, false, false),
    IPv4("IPv4", false, false, true, false, false, false, true, true, false, false),
    IPv6("IPv6", false, false, true, false, false, false, true, true, false, false),
    Point("Point", false, false, true, false, false, false, true, true, false, false),
    Ring("Ring", false, false, true, false, false, false, true, true, false, false),
    Polygon("Polygon", false, false, true, false, false, false, true, true, false, false),
    MultiPolygon("MultiPolygon", false, false, true, false, false, false, true, true, false, false),
    AggregateFunction("AggregateFunction", true, true, true, false, false, false, true, true, false, false),
    SimpleAggregateFunction("SimpleAggregateFunction", true, true, true, false, false, false, true, true, false, false),
    ;





    private static Map<String, ClickHouseColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (ClickHouseColumnTypeEnum value : ClickHouseColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName().toUpperCase(), value);
        }
    }

    private ColumnType columnType;


    ClickHouseColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale, boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset, boolean supportCollation, boolean supportComments, boolean supportDefaultValue, boolean supportExtent, boolean supportValue) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable, supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue, supportExtent, supportValue, false);
    }

    public static ClickHouseColumnTypeEnum getByType(String dataType) {
        if (dataType == null) {
            return null;
        }
        String normalized = dataType.trim();
        if (normalized.indexOf('(') >= 0) {
            // Parameterized forms (Decimal(10,2), Array(...), Enum8('a','b'), ...)
            // go through the validated fallback so the arguments are preserved
            // and checked instead of silently dropped.
            return null;
        }
        return COLUMN_TYPE_MAP.get(normalized.toUpperCase(Locale.ROOT));
    }

    /**
     * Builds the column definition for any declared column type, falling back
     * to a fail-closed validated expression for types outside the enum.
     */
    public static String buildCreateColumnSqlSafely(TableColumn column) {
        ClickHouseColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildValidatedFallbackColumn(column);
        }
        return type.buildCreateColumnSql(column);
    }

    /**
     * Builds an ALTER action while preserving parameterized and newer
     * ClickHouse types that are not represented by this enum.
     */
    public static String buildModifyColumnSqlSafely(TableColumn column) {
        return buildModifyColumnSql(column, buildCreateColumnSqlSafely(column).stripTrailing());
    }

    private static String buildValidatedFallbackColumn(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");
        String columnType = ClickHouseSqlGuards.requireColumnTypeExpression(column.getColumnType());
        if (column.getNullable() != null && 1 == column.getNullable() && isNullableWrappable(columnType)) {
            columnType = "Nullable(" + columnType + ")";
        }
        script.append(columnType).append(" ");
        String defaultValue = buildFallbackDefaultValue(column);
        if (StringUtils.isNotBlank(defaultValue)) {
            script.append(defaultValue).append(" ");
        }
        if (StringUtils.isNotBlank(column.getComment())) {
            script.append("COMMENT '").append(ClickHouseIdentifierProcessor.INSTANCE.escapeString(column.getComment())).append("' ");
        }
        return script.toString();
    }

    private static boolean isNullableWrappable(String columnType) {
        String upper = columnType.toUpperCase(Locale.ROOT);
        return !upper.startsWith("ARRAY(") && !upper.startsWith("MAP(") && !upper.startsWith("TUPLE(")
                && !upper.startsWith("NESTED(") && !upper.startsWith("NULLABLE(")
                && !upper.startsWith("AGGREGATEFUNCTION(") && !upper.startsWith("SIMPLEAGGREGATEFUNCTION(");
    }

    private static String buildFallbackDefaultValue(TableColumn column) {
        if (StringUtils.isEmpty(column.getDefaultValue())) {
            return "";
        }
        if ("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return "DEFAULT ''";
        }
        if ("NULL".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return "DEFAULT NULL";
        }
        String typeName = column.getColumnType().trim();
        int arguments = typeName.indexOf('(');
        if (arguments >= 0) {
            typeName = typeName.substring(0, arguments);
        }
        if ("ENUM8".equalsIgnoreCase(typeName) || "ENUM16".equalsIgnoreCase(typeName)
                || "DATE".equalsIgnoreCase(typeName) || "DATE32".equalsIgnoreCase(typeName)) {
            return buildTextDefault(column.getDefaultValue());
        }
        if ("DATETIME".equalsIgnoreCase(typeName) || "DATETIME64".equalsIgnoreCase(typeName)) {
            String trimmed = column.getDefaultValue().trim();
            if ("CURRENT_TIMESTAMP".equalsIgnoreCase(trimmed) || trimmed.indexOf('(') >= 0) {
                return "DEFAULT " + ClickHouseSqlGuards.escapeDefaultExpression(trimmed);
            }
            return buildTextDefault(column.getDefaultValue());
        }
        return "DEFAULT " + ClickHouseSqlGuards.escapeDefaultExpression(column.getDefaultValue());
    }

    public static List<ColumnType> getTypes() {
        return Arrays.stream(ClickHouseColumnTypeEnum.values()).map(columnTypeEnum ->
                columnTypeEnum.getColumnType()
        ).toList();
    }

    public ColumnType getColumnType() {
        return columnType;
    }

    @Override
    public String buildCreateColumnSql(TableColumn column) {
        ClickHouseColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildValidatedFallbackColumn(column);
        }
        StringBuilder script = new StringBuilder();

        script.append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");

        script.append(buildNullableAndDataType(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        script.append(buildComment(column, type)).append(" ");

        return script.toString();
    }

    @Override
    public String buildModifyColumn(TableColumn tableColumn) {
        return buildModifyColumnSql(tableColumn, buildCreateColumnSql(tableColumn).stripTrailing());
    }

    private static String buildModifyColumnSql(TableColumn column, String definition) {
        if (EditStatusEnum.DELETE.name().equals(column.getEditStatus())) {
            return "DROP COLUMN " + ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName());
        }
        if (EditStatusEnum.ADD.name().equals(column.getEditStatus())) {
            return "ADD COLUMN " + definition;
        }
        if (EditStatusEnum.MODIFY.name().equals(column.getEditStatus())) {
            String modify = "MODIFY COLUMN " + definition;
            if (!StringUtils.equals(column.getOldName(), column.getName())) {
                return "RENAME COLUMN "
                        + ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getOldName())
                        + " TO " + ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())
                        + ",\n\t" + modify;
            }
            return modify;
        }
        return "";
    }

    private String buildComment(TableColumn column, ClickHouseColumnTypeEnum type) {
        if (!type.columnType.isSupportComments() || StringUtils.isEmpty(column.getComment())) {
            return "";
        }
        return StringUtils.join(SQL_COMMENT_2, ClickHouseIdentifierProcessor.INSTANCE.escapeString(column.getComment()), "'");
    }

    private String buildDefaultValue(TableColumn column, ClickHouseColumnTypeEnum type) {
        if (!type.getColumnType().isSupportDefaultValue() || StringUtils.isEmpty(column.getDefaultValue())) {
            return "";
        }

        if ("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT ''");
        }

        if ("NULL".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT NULL");
        }

        if (Arrays.asList(Enum8, Enum16).contains(type)) {
            return buildTextDefault(column.getDefaultValue());
        }

        if (Arrays.asList(Date, DATE32).contains(type)) {
            return buildTextDefault(column.getDefaultValue());
        }

        if (Arrays.asList(DateTime, DateTime64).contains(type)) {
            String trimmed = column.getDefaultValue().trim();
            if ("CURRENT_TIMESTAMP".equalsIgnoreCase(trimmed) || trimmed.indexOf('(') >= 0) {
                return "DEFAULT " + ClickHouseSqlGuards.escapeDefaultExpression(trimmed);
            }
            return buildTextDefault(column.getDefaultValue());
        }

        return StringUtils.join("DEFAULT ", ClickHouseSqlGuards.escapeDefaultExpression(column.getDefaultValue()));
    }

    private static String buildTextDefault(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("'") || trimmed.endsWith("'")) {
            return "DEFAULT " + ClickHouseSqlGuards.escapeDefaultExpression(trimmed);
        }
        return "DEFAULT '" + ClickHouseIdentifierProcessor.INSTANCE.escapeString(value) + "'";
    }

    private String buildNullableAndDataType(TableColumn column, ClickHouseColumnTypeEnum type) {
        StringBuilder script = new StringBuilder();
        script.append(buildDataType(column, type));

        if (!type.getColumnType().isSupportNullable()) {
            return script.toString();
        }
        if (column.getNullable() != null && 1 == column.getNullable()) {
            return "Nullable("+script.append(")").toString();
        } else {
            return script.toString();
        }
    }

    private String buildDataType(TableColumn column, ClickHouseColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();
        if (Arrays.asList(FixedString).contains(type)) {
            return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
        }


        if (Arrays.asList(Decimal).contains(type)) {
            if (column.getColumnSize() == null) {
                return columnType;
            }
            if (column.getDecimalDigits() == null) {
                return StringUtils.join(columnType, "(", column.getColumnSize(), ",0)");
            }
            return StringUtils.join(columnType, "(", column.getColumnSize(), ",", column.getDecimalDigits(), ")");
        }

        return columnType;


    }

    public String buildColumn(TableColumn column) {
        ClickHouseColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return "";
        }
        StringBuilder script = new StringBuilder();

        script.append(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");
        script.append(buildDataType(column, type)).append(" ");
        if (StringUtils.isNoneBlank(column.getComment())) {
            script.append(SQL_COMMENT).append(" ").append("'").append(ClickHouseIdentifierProcessor.INSTANCE.escapeString(column.getComment())).append("'").append(" ");
        }
        return script.toString();
    }

    private String unsignedDataType(String dataTypeName, String middle) {
        String[] split = dataTypeName.split(" ");
        if (split.length == 2) {
            return StringUtils.join(split[0], middle, split[1]);
        }
        return StringUtils.join(dataTypeName, middle);
    }

}
