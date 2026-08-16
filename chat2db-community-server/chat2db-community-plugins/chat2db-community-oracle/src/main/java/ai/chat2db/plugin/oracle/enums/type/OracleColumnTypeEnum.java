package ai.chat2db.plugin.oracle.enums.type;

import ai.chat2db.plugin.oracle.OracleSqlGuards;
import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;
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
import java.util.Objects;

import static ai.chat2db.plugin.oracle.constant.OracleColumnTypeEnumConstants.*;
public enum OracleColumnTypeEnum implements IColumnBuilder {

    BFILE("BFILE", false, false, true, false, false, false, true, true, false, false),

    BINARY_DOUBLE("BINARY_DOUBLE", false, false, true, false, false, false, true, true, false, false),


    BINARY_FLOAT("BINARY_FLOAT", false, false, true, false, false, false, true, true, false, false),


    BLOB("BLOB", false, false, true, false, false, false, true, true, false, false),


    CHAR("CHAR", true, false, true, false, false, false, true, true, false, true),

    CHAR_VARYING("CHAR VARYING", true, false, true, false, false, false, true, true, false, true),

    CHARACTER("CHARACTER", true, false, true, false, false, false, true, true, false, true),

    CHARACTER_VARYING("CHARACTER VARYING", true, false, true, false, false, false, true, true, false, true),

    CLOB("CLOB", false, false, true, false, false, false, true, true, false, false),

    DATE("DATE", false, false, true, false, false, false, true, true, false, false),

    DECIMAL("DECIMAL", true, true, true, false, false, false, true, true, false, false),

    DOUBLE_PRECISION("DOUBLE PRECISION", false, false, true, false, false, false, true, true, false, false),


    FLOAT("FLOAT", true, false, true, false, false, false, true, true, false, false),

    INT("INT", false, false, true, false, false, false, true, true, false, false),

    INTEGER("INTEGER", false, false, true, false, false, false, true, true, false, false),

    LONG("LONG", false, false, true, false, false, false, true, true, false, false),

    LONG_RAW("LONG RAW", false, false, true, false, false, false, true, true, false, false),


    LONG_VARCHAR("LONG VARCHAR", false, false, true, false, false, false, true, true, false, false),

    NATIONAL_CHAR("NATIONAL CHAR", true, false, true, false, false, false, true, true, false, true),


    NATIONAL_CHAR_VARYING("NATIONAL CHAR VARYING", true, false, true, false, false, false, true, true, false, true),


    NATIONAL_CHARACTER("NATIONAL CHARACTER", true, false, true, false, false, false, true, true, false, true),


    NATIONAL_CHARACTER_VARYING("NATIONAL CHARACTER VARYING", true, false, true, false, false, false, true, true, false, true),

    NCHAR("NCHAR", true, false, true, false, false, false, true, true, false, false),

    NCHAR_VARYING("NCHAR VARYING", true, false, true, false, false, false, true, true, false, false),

    NCLOB("NCLOB", false, false, true, false, false, false, true, true, false, false),

    NUMBER("NUMBER", true, true, true, false, false, false, true, true, false, false),


    NVARCHAR2("NVARCHAR2", true, false, true, false, false, false, true, true, false, true),

    RAW("RAW", true, false, true, false, false, false, true, true, false, false),

    REAL("REAL", false, false, true, false, false, false, true, true, false, false),

    ROWID("ROWID", false, false, true, false, false, false, true, true, false, false),


    SMALLINT("SMALLINT", false, false, true, false, false, false, true, true, false, false),

    TIMESTAMP("TIMESTAMP", false, true, true, false, false, false, true, true, false, false),

    TIMESTAMP_WITH_LOCAL_TIME_ZONE("TIMESTAMP WITH LOCAL TIME ZONE", false, true, true, false, false, false, true, true, false, false),


    TIMESTAMP_WITH_TIME_ZONE("TIMESTAMP WITH TIME ZONE", false, true, true, false, false, false, true, true, false, false),


    INTERVAL_YEAR_TO_MONTH("INTERVAL YEAR TO MONTH", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_DAY_TO_SECOND("INTERVAL DAY TO SECOND", true, true, true, false, false, false, true, true, false, false),

    UROWID("UROWID", true, false, true, false, false, false, true, true, false, false),

    VARCHAR("VARCHAR", true, false, true, false, false, false, true, true, false, true),

    VARCHAR2("VARCHAR2", true, false, true, false, false, false, true, true, false, true),

    XMLTYPE("XMLTYPE", false, false, true, false, false, false, true, true, false, false),

    ;




    private ColumnType columnType;

    public static OracleColumnTypeEnum getByType(String dataType) {
        if (StringUtils.isBlank(dataType)) {
            return null;
        }
        String normalized = dataType.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return COLUMN_TYPE_MAP.get(normalized);
    }

    private static Map<String, OracleColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (OracleColumnTypeEnum value : OracleColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName(), value);
        }
    }

    public ColumnType getColumnType() {
        return columnType;
    }


    OracleColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale, boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset, boolean supportCollation, boolean supportComments, boolean supportDefaultValue, boolean supportExtent, boolean supportUnit) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable, supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue, supportExtent, false, supportUnit);
    }

    @Override
    public String buildCreateColumnSql(TableColumn column) {
        OracleColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildUnknownColumnSql(column);
        }
        StringBuilder script = new StringBuilder();

        script.append(OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        script.append(buildNullable(column, type)).append(" ");

        return script.toString();
    }

    @Override
    public String buildAICreateColumnSql(TableColumn column) {
        OracleColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildUnknownColumnSql(column);
        }
        StringBuilder script = new StringBuilder();

        script.append(OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        script.append(buildAICreateColumnCommentSql(column)).append(" ");

        return script.toString();
    }


    private String buildNullable(TableColumn column, OracleColumnTypeEnum type) {
        if (!type.getColumnType().isSupportNullable()) {
            return "";
        }
        if (column.getNullable() != null && 1 == column.getNullable()) {
            return "NULL";
        } else {
            return "NOT NULL";
        }
    }

    private String buildDefaultValue(TableColumn column, OracleColumnTypeEnum type) {
        if (!type.getColumnType().isSupportDefaultValue() || StringUtils.isEmpty(column.getDefaultValue())) {
            return "";
        }

        if ("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT ''");
        }

        if ("NULL".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT NULL");
        }

        return StringUtils.join("DEFAULT ", OracleSqlGuards.requireDefaultValue(column.getDefaultValue()));
    }

    private String buildDataType(TableColumn column, OracleColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();
        if (Arrays.asList(CHAR, CHAR_VARYING, CHARACTER, CHARACTER_VARYING,
                NVARCHAR2, VARCHAR, VARCHAR2, NATIONAL_CHAR,
                NATIONAL_CHAR_VARYING, NATIONAL_CHARACTER,
                NATIONAL_CHARACTER_VARYING, NCHAR, NCHAR_VARYING).contains(type)) {
            StringBuilder script = new StringBuilder();
            script.append(columnType);
            if (column.getColumnSize() != null && StringUtils.isEmpty(column.getUnit())) {
                script.append("(").append(column.getColumnSize()).append(")");
            } else if (column.getColumnSize() != null && !StringUtils.isEmpty(column.getUnit())) {
                script.append("(").append(column.getColumnSize()).append(" ").append(OracleSqlGuards.requireUnit(column.getUnit())).append(")");
            }
            return script.toString();
        }

        if (Arrays.asList(DECIMAL, FLOAT, NUMBER, UROWID, RAW).contains(type)) {
            StringBuilder script = new StringBuilder();
            script.append(columnType);
            if (column.getColumnSize() != null && column.getDecimalDigits() == null) {
                script.append("(").append(column.getColumnSize()).append(")");
            } else if (column.getColumnSize() != null && column.getDecimalDigits() != null) {
                script.append("(").append(column.getColumnSize()).append(",").append(column.getDecimalDigits()).append(")");
            }
            return script.toString();
        }
        if (Arrays.asList(TIMESTAMP).contains(type)) {
            int decimalDigits = column.getDecimalDigits() != null ? column.getDecimalDigits() : 6;
            String valueTemplate = "TIMESTAMP(%s)";
            return String.format(valueTemplate, decimalDigits);
        }
        if (Arrays.asList(TIMESTAMP_WITH_TIME_ZONE).contains(type)) {
            int decimalDigits = column.getDecimalDigits() != null ? column.getDecimalDigits() : 6;
            String valueTemplate = "TIMESTAMP(%s) WITH TIME ZONE";
            return String.format(valueTemplate, decimalDigits);
        }
        if (Arrays.asList(TIMESTAMP_WITH_LOCAL_TIME_ZONE).contains(type)) {
            int decimalDigits = column.getDecimalDigits() != null ? column.getDecimalDigits() : 6;
            String valueTemplate = "TIMESTAMP(%s) WITH LOCAL TIME ZONE";
            return String.format(valueTemplate, decimalDigits);
        }
        if (Arrays.asList(INTERVAL_DAY_TO_SECOND).contains(type)) {
            int columnSize = column.getColumnSize() != null ? column.getColumnSize() : 2;
            int decimalDigits = column.getDecimalDigits() != null ? column.getDecimalDigits() : 6;
            String valueTemplate = "INTERVAL DAY(%s) TO SECOND(%s)";
            return String.format(valueTemplate, columnSize, decimalDigits);
        }
        if (Arrays.asList(INTERVAL_YEAR_TO_MONTH).contains(type)) {
            int columnSize = column.getColumnSize() != null ? column.getColumnSize() : 2;
            String valueTemplate = "INTERVAL YEAR(%s) TO MONTH";
            return String.format(valueTemplate, columnSize);
        }


        return columnType;
    }


    @Override
    public String buildModifyColumn(TableColumn tableColumn) {

        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            script.append(SQL_ALTER_TABLE).append(qualifiedTableName(tableColumn));
            script.append(" ").append(SQL_DROP_COLUMN).append(OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getName()));
            return script.toString();
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            script.append(SQL_ALTER_TABLE).append(qualifiedTableName(tableColumn));
            script.append(" ").append("ADD (").append(buildCreateColumnSql(tableColumn)).append(")");
            return script.toString();
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            script.append(SQL_ALTER_TABLE).append(qualifiedTableName(tableColumn));
            script.append(" ").append("MODIFY (").append(buildModifyColumnSql(tableColumn, tableColumn.getOldColumn())).append(") \n");

            if (!StringUtils.equals(tableColumn.getOldName(), tableColumn.getName())) {
                script.append(";");
                script.append(SQL_ALTER_TABLE).append(qualifiedTableName(tableColumn));
                script.append(" ").append(SQL_RENAME_COLUMN)
                        .append(OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getOldName()))
                        .append(" TO ")
                        .append(OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getName()));

            }
            return script.toString();

        }
        return "";
    }

    public String buildModifyColumnSql(TableColumn column, TableColumn oldColumn) {
        OracleColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildUnknownColumnSql(column);
        }
        StringBuilder script = new StringBuilder();

        script.append(OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        if (oldColumn != null && !Objects.equals(oldColumn.getNullable(), column.getNullable())) {
            script.append(buildNullable(column, type)).append(" ");
        }

        return script.toString();
    }

    private static String buildUnknownColumnSql(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName()))
                .append(" ")
                .append(OracleSqlGuards.requireColumnTypeExpression(column.getColumnType()));
        if (StringUtils.isNotEmpty(column.getDefaultValue())) {
            String defaultValue = column.getDefaultValue();
            if ("EMPTY_STRING".equalsIgnoreCase(defaultValue.trim())) {
                script.append(" DEFAULT ''");
            } else if ("NULL".equalsIgnoreCase(defaultValue.trim())) {
                script.append(" DEFAULT NULL");
            } else {
                script.append(" DEFAULT ").append(OracleSqlGuards.requireDefaultValue(defaultValue));
            }
        }
        if (column.getNullable() != null) {
            script.append(column.getNullable() == 1 ? " NULL" : " NOT NULL");
        }
        return script.toString();
    }

    private static String qualifiedTableName(TableColumn column) {
        String tableName = OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getTableName());
        if (StringUtils.isBlank(column.getSchemaName())) {
            return tableName;
        }
        return OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getSchemaName()) + "." + tableName;
    }

    public static List<ColumnType> getTypes() {
        return Arrays.stream(OracleColumnTypeEnum.values()).map(columnTypeEnum ->
                columnTypeEnum.getColumnType()
        ).toList();
    }
}
