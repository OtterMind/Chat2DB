package ai.chat2db.plugin.oscar.enums.type;

import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.plugin.oscar.OscarSqlGuards;
import ai.chat2db.plugin.oscar.constant.OscarConstants;
import ai.chat2db.plugin.oscar.util.OscarUtils;
import ai.chat2db.spi.IColumnBuilder;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.util.SqlUtils;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public enum OscarColumnTypeEnum implements IColumnBuilder {

    BIGINT("BIGINT", false, false, true, false, false, false, true, true, false, false),
    BINARY("BINARY", true, false, true, false, false, false, true, true, false, false),
    BINARY_DOUBLE("BINARY_DOUBLE", false, false, true, false, false, false, true, true, false, false),
    BINARY_FLOAT("BINARY_FLOAT", false, false, true, false, false, false, true, true, false, false),
    BLOB("BLOB", false, false, true, false, false, false, true, true, false, false),
    BOOL("BOOL", false, false, true, false, false, false, true, true, false, false),
    BOOLEAN("BOOLEAN", false, false, true, false, false, false, true, true, false, false),
    CHAR("CHAR", true, false, true, false, false, false, true, true, false, true),
    CHARACTER("CHARACTER", true, false, true, false, false, false, true, true, false, true),
    CHARACTER_VARYING("CHARACTER VARYING", true, false, true, false, false, false, true, true, false, true),
    CLOB("CLOB", false, false, true, false, false, false, true, true, false, false),
    DATE("DATE", false, false, true, false, false, false, true, true, false, false),
    DEC("DEC", true, true, true, false, false, false, true, true, false, false),
    DECIMAL("DECIMAL", true, true, true, false, false, false, true, true, false, false),
    DOUBLE("DOUBLE", false, false, true, false, false, false, true, true, false, false),
    DOUBLE_PRECISION("DOUBLE PRECISION", false, false, true, false, false, false, true, true, false, false),
    FLOAT("FLOAT", true, false, true, false, false, false, true, true, false, false),
    INT("INT", false, false, true, false, false, false, true, true, false, false),
    INT2("INT2", false, false, true, false, false, false, true, true, false, false),
    INT4("INT4", false, false, true, false, false, false, true, true, false, false),
    INT8("INT8", false, false, true, false, false, false, true, true, false, false),
    INTEGER("INTEGER", false, false, true, false, false, false, true, true, false, false),
    INTERVAL_DAY_TO_SECOND("INTERVAL DAY TO SECOND", true, true, true, false, false, false, true, true, false, false),
    INTERVAL_YEAR_TO_MONTH("INTERVAL YEAR TO MONTH", true, false, true, false, false, false, true, true, false, false),
    LONG_VARCHAR("LONG VARCHAR", false, false, true, false, false, false, true, true, false, false),
    LONGVARBINARY("LONGVARBINARY", false, false, true, false, false, false, true, true, false, false),
    LONGVARCHAR("LONGVARCHAR", false, false, true, false, false, false, true, true, false, false),
    NUMERIC("NUMERIC", true, true, true, false, false, false, true, true, false, false),
    NUMBER("NUMBER", true, true, true, false, false, false, true, true, false, false),
    RAW("RAW", true, false, true, false, false, false, true, true, false, false),
    REAL("REAL", false, false, true, false, false, false, true, true, false, false),
    SMALLINT("SMALLINT", false, false, true, false, false, false, true, true, false, false),
    TEXT("TEXT", false, false, true, false, false, false, true, true, false, false),
    TIME("TIME", false, false, true, false, false, false, true, true, false, false),
    TIME_WITH_TIME_ZONE("TIME WITH TIME ZONE", false, false, true, false, false, false, true, true, false, false),
    TIMESTAMP("TIMESTAMP", true, false, true, false, false, false, true, true, false, false),
    TIMESTAMP_WITH_LOCAL_TIME_ZONE("TIMESTAMP WITH LOCAL TIME ZONE", true, false, true, false, false, false, true, true, false, false),
    TIMESTAMP_WITH_TIME_ZONE("TIMESTAMP WITH TIME ZONE", true, false, true, false, false, false, true, true, false, false),
    TINYINT("TINYINT", false, false, true, false, false, false, true, true, false, false),
    VARCHAR("VARCHAR", true, false, true, false, false, false, true, true, false, true),
    VARCHAR2("VARCHAR2", true, false, true, false, false, false, true, true, false, true);

    private final ColumnType columnType;

    private static final Map<String, OscarColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (OscarColumnTypeEnum value : OscarColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName(), value);
        }
    }

    OscarColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale,
                           boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset,
                           boolean supportCollation, boolean supportComments, boolean supportDefaultValue,
                           boolean supportExtent, boolean supportUnit) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable,
                supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue,
                supportExtent, false, supportUnit);
    }

    public ColumnType getColumnType() {
        return columnType;
    }

    public static OscarColumnTypeEnum getByType(String dataType) {
        if (StringUtils.isBlank(dataType)) {
            return null;
        }
        return COLUMN_TYPE_MAP.get(SqlUtils.removeDigits(dataType.toUpperCase()));
    }

    @Override
    public String buildCreateColumnSql(TableColumn column) {
        OscarColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildUnknownColumnSql(column);
        }
        StringBuilder script = new StringBuilder();
        script.append(OscarUtils.quoteIdentifierIgnoreCase(column.getName())).append(SQLConstants.SPACE);
        script.append(buildDataType(column, type)).append(SQLConstants.SPACE);
        script.append(buildDefaultValue(column, type)).append(SQLConstants.SPACE);
        script.append(buildNullable(column, type)).append(SQLConstants.SPACE);
        return script.toString();
    }

    @Override
    public String buildAICreateColumnSql(TableColumn column) {
        return buildCreateColumnSql(column) + buildAICreateColumnCommentSql(column);
    }

    private static String buildUnknownColumnSql(TableColumn column) {
        StringBuilder script = new StringBuilder(OscarUtils.quoteIdentifierIgnoreCase(column.getName()))
                .append(SQLConstants.SPACE)
                .append(OscarSqlGuards.requireColumnTypeExpression(column.getColumnType()));
        if (StringUtils.isNotBlank(column.getDefaultValue())) {
            String defaultValue = column.getDefaultValue().trim();
            if (OscarConstants.EMPTY_STRING_TOKEN.equalsIgnoreCase(defaultValue)) {
                script.append(SQLConstants.SPACE).append(SQLConstants.DEFAULT_EMPTY_STRING_SQL);
            } else if (OscarConstants.NULL_TOKEN.equalsIgnoreCase(defaultValue)) {
                script.append(SQLConstants.SPACE).append(SQLConstants.DEFAULT_NULL_SQL);
            } else {
                script.append(SQLConstants.SPACE).append(SQLConstants.DEFAULT_SQL_PREFIX)
                        .append(OscarSqlGuards.requireDefaultValueExpression(defaultValue));
            }
        }
        if (column.getNullable() != null && column.getNullable() != 1) {
            script.append(SQLConstants.SPACE).append(SQLConstants.NOT_NULL_SQL);
        }
        return script.toString();
    }

    @Override
    public String buildModifyColumn(TableColumn tableColumn) {
        String tableName = OscarUtils.qualifiedName(tableColumn.getSchemaName(), tableColumn.getTableName());
        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            return SQLConstants.ALTER_TABLE_SQL_PREFIX + tableName
                    + SQLConstants.COLUMN_DROP_SQL + OscarUtils.quoteIdentifierIgnoreCase(tableColumn.getName());
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            return SQLConstants.ALTER_TABLE_SQL_PREFIX + tableName
                    + SQLConstants.COLUMN_ADD_SQL + buildCreateColumnSql(tableColumn)
                    + SQLConstants.CLOSE_PARENTHESIS;
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            if (StringUtils.isNotBlank(tableColumn.getOldName())
                    && !StringUtils.equals(tableColumn.getOldName(), tableColumn.getName())) {
                script.append(SQLConstants.ALTER_TABLE_SQL_PREFIX).append(tableName)
                        .append(SQLConstants.COLUMN_RENAME_SQL)
                        .append(OscarUtils.quoteIdentifierIgnoreCase(tableColumn.getOldName()))
                        .append(SQLConstants.SQL_TO)
                        .append(OscarUtils.quoteIdentifierIgnoreCase(tableColumn.getName()))
                        .append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            }
            script.append(SQLConstants.ALTER_TABLE_SQL_PREFIX).append(tableName)
                    .append(SQLConstants.COLUMN_MODIFY_SQL)
                    .append(buildCreateColumnSql(tableColumn))
                    .append(SQLConstants.CLOSE_PARENTHESIS);
            return script.toString();
        }
        return SQLConstants.EMPTY;
    }

    private String buildNullable(TableColumn column, OscarColumnTypeEnum type) {
        if (!type.getColumnType().isSupportNullable()) {
            return "";
        }
        if (column.getNullable() == null || 1 == column.getNullable()) {
            return SQLConstants.EMPTY;
        }
        return SQLConstants.NOT_NULL_SQL;
    }

    private String buildDefaultValue(TableColumn column, OscarColumnTypeEnum type) {
        if (!type.getColumnType().isSupportDefaultValue() || StringUtils.isBlank(column.getDefaultValue())) {
            return SQLConstants.EMPTY;
        }
        String defaultValue = column.getDefaultValue().trim();
        if (OscarConstants.EMPTY_STRING_TOKEN.equalsIgnoreCase(defaultValue)) {
            return SQLConstants.DEFAULT_EMPTY_STRING_SQL;
        }
        if (OscarConstants.NULL_TOKEN.equalsIgnoreCase(defaultValue)) {
            return SQLConstants.DEFAULT_NULL_SQL;
        }
        return SQLConstants.DEFAULT_SQL_PREFIX + OscarSqlGuards.requireDefaultValueExpression(defaultValue);
    }

    private String buildDataType(TableColumn column, OscarColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();
        if (Arrays.asList(BINARY, CHAR, CHARACTER, CHARACTER_VARYING, RAW, VARCHAR, VARCHAR2).contains(type)) {
            return buildLengthType(column, columnType);
        }
        if (Arrays.asList(DEC, DECIMAL, FLOAT, NUMBER, NUMERIC).contains(type)) {
            return buildNumberType(column, columnType);
        }
        if (Arrays.asList(TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE, TIMESTAMP_WITH_TIME_ZONE).contains(type)) {
            return buildTimestampType(column, columnType);
        }
        if (Objects.equals(INTERVAL_DAY_TO_SECOND, type)) {
            int columnSize = column.getColumnSize() == null ? 2 : column.getColumnSize();
            int decimalDigits = column.getDecimalDigits() == null ? 6 : column.getDecimalDigits();
            return String.format(OscarConstants.INTERVAL_DAY_TO_SECOND_SQL_TEMPLATE, columnSize, decimalDigits);
        }
        if (Objects.equals(INTERVAL_YEAR_TO_MONTH, type)) {
            int columnSize = column.getColumnSize() == null ? 2 : column.getColumnSize();
            return String.format(OscarConstants.INTERVAL_YEAR_TO_MONTH_SQL_TEMPLATE, columnSize);
        }
        return columnType;
    }

    private String buildLengthType(TableColumn column, String columnType) {
        StringBuilder script = new StringBuilder(columnType);
        if (column.getColumnSize() != null) {
            script.append(SQLConstants.OPEN_PARENTHESIS).append(column.getColumnSize());
            if (StringUtils.isNotBlank(column.getUnit())) {
                script.append(SQLConstants.SPACE).append(OscarSqlGuards.requireLengthUnit(column.getUnit()));
            }
            script.append(SQLConstants.CLOSE_PARENTHESIS);
        }
        return script.toString();
    }

    private String buildNumberType(TableColumn column, String columnType) {
        StringBuilder script = new StringBuilder(columnType);
        if (column.getColumnSize() != null) {
            script.append(SQLConstants.OPEN_PARENTHESIS).append(column.getColumnSize());
            if (column.getDecimalDigits() != null) {
                script.append(SQLConstants.COMMA).append(column.getDecimalDigits());
            }
            script.append(SQLConstants.CLOSE_PARENTHESIS);
        }
        return script.toString();
    }

    private String buildTimestampType(TableColumn column, String columnType) {
        Integer precision = column.getColumnSize();
        if (precision == null) {
            precision = column.getDecimalDigits();
        }
        if (precision == null) {
            return columnType;
        }
        return columnType.replace(TIMESTAMP.getColumnType().getTypeName(),
                TIMESTAMP.getColumnType().getTypeName() + SQLConstants.OPEN_PARENTHESIS + precision
                        + SQLConstants.CLOSE_PARENTHESIS);
    }

    public static List<ColumnType> getTypes() {
        return Arrays.stream(OscarColumnTypeEnum.values())
                .map(OscarColumnTypeEnum::getColumnType)
                .toList();
    }
}
