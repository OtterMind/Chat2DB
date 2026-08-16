package ai.chat2db.plugin.dm.enums.type;

import ai.chat2db.plugin.dm.DMSqlGuards;
import ai.chat2db.plugin.dm.identifier.DMIdentifierProcessor;
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

import static ai.chat2db.plugin.dm.constant.DMColumnTypeEnumConstants.*;
public enum DMColumnTypeEnum implements IColumnBuilder {


    BFILE("BFILE", false, false, true, false, false, false, true, true, false, false),

    BIGINT("BIGINT", false, false, true, false, false, false, true, true, false, false),


    BINARY("BINARY", false, false, true, false, false, false, true, true, false, false),


    BIT("BIT", false, false, true, false, false, false, true, true, false, false),


    BLOB("BLOB", false, false, true, false, false, false, true, true, false, false),


    CHAR("CHAR", true, false, true, false, false, false, true, true, false, true),

    CLOB("CLOB", false, false, true, false, false, false, true, true, false, false),

    DATE("DATE", false, false, true, false, false, false, true, true, false, false),

    DECIMAL("DECIMAL", true, true, true, false, false, false, true, true, false, false),
    DEC("DEC", true, true, true, false, false, false, true, true, false, false),

    DOUBLE("DOUBLE", false, false, true, false, false, false, true, true, false, false),


    FLOAT("FLOAT", true, false, true, false, false, false, true, true, false, false),
    DOUBLE_PRECISION("DOUBLE PRECISION", true, false, true, false, false, false, true, true, false, false),

    INT("INT", false, false, true, true, false, false, true, true, false, false),

     INTEGER("INTEGER", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_DAY("INTERVAL DAY", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_DAY_TO_HOUR("INTERVAL DAY TO HOUR", true, false, true, false, false, false, true, true, false, false),


    INTERVAL_DAY_TO_MINUTE("INTERVAL DAY TO MINUTE", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_DAY_TO_SECOND("INTERVAL DAY TO SECOND", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_HOUR("INTERVAL HOUR", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_HOUR_TO_MINUTE("INTERVAL HOUR TO MINUTE", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_HOUR_TO_SECOND("INTERVAL HOUR TO SECOND", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_MINUTE("INTERVAL MINUTE", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_MINUTE_TO_SECOND("INTERVAL MINUTE TO SECOND", true, false, true, false, false, false, true, true, false, false),


    INTERVAL_MONTH("INTERVAL MONTH", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_SECOND("INTERVAL SECOND", false, false, true, false, false, false, true, true, false, false),


    INTERVAL_YEAR("INTERVAL YEAR", false, false, true, false, false, false, true, true, false, false),


    INTERVAL_YEAR_TO_MONTH("INTERVAL YEAR TO MONTH", true, false, true, false, false, false, true, true, false, false),

    LONGVARBINARY("LONGVARBINARY", false, false, true, false, false, false, true, true, false, false),


    LONGVARCHAR("LONGVARCHAR", false, false, true, false, false, false, true, true, false, false),
    TEXT("TEXT", false, false, true, false, false, false, true, true, false, false),


    NUMERIC("NUMERIC", true, true, true, false, false, false, true, true, false, false),


    NUMBER("NUMBER", true, true, true, false, false, false, true, true, false, false),

    REAL("REAL", false, false, true, false, false, false, true, true, false, false),


    SMALLINT("SMALLINT", false, false, true, false, false, false, true, true, false, false),


    TIME("TIME", false, false, true, false, false, false, true, true, false, false),


    TIME_WITH_TIME_ZONE("TIME WITH TIME ZONE", false, false, true, false, false, false, true, true, false, false),


    TIMESTAMP("TIMESTAMP", true, false, true, false, false, false, true, true, false, false),


    TIMESTAMP_WITH_TIME_ZONE("TIMESTAMP WITH TIME ZONE", false, false, true, false, false, false, true, true, false, false),


    TINYINT("TINYINT", false, false, true, false, false, false, true, true, false, false),


    VARBINARY("VARBINARY", false, false, true, false, false, false, true, true, false, false),


    VARCHAR("VARCHAR", true, false, true, false, false, false, true, true, false, true),


    VARCHAR2("VARCHAR2", true, false, true, false, false, false, true, true, false, true),

    DATETIME("DATETIME", false, false, true, false, false, false, true, true, false, false),
    ;




    private ColumnType columnType;

    public static DMColumnTypeEnum getByType(String dataType) {
        if (StringUtils.isBlank(dataType)) {
            return null;
        }
        return COLUMN_TYPE_MAP.get(StringUtils.normalizeSpace(dataType).toUpperCase(Locale.ROOT));
    }

    private static Map<String, DMColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (DMColumnTypeEnum value : DMColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName(), value);
        }
    }

    public ColumnType getColumnType() {
        return columnType;
    }


    DMColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale, boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset, boolean supportCollation, boolean supportComments, boolean supportDefaultValue, boolean supportExtent, boolean supportUnit) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable, supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue, supportExtent, false, supportUnit);
    }

    @Override
    public String buildCreateColumnSql(TableColumn column) {
        DMColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildUnknownColumnSql(column);
        }
        StringBuilder script = new StringBuilder();

        script.append(DMIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        script.append(buildAutoIncrement(column,type)).append(" ");

        script.append(buildNullable(column, type)).append(" ");

        return script.toString();
    }

    @Override
    public String buildAICreateColumnSql(TableColumn column) {
        DMColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildUnknownColumnSql(column);
        }
        StringBuilder script = new StringBuilder();

        script.append(DMIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        script.append(buildAutoIncrement(column,type)).append(" ");

        script.append(buildNullable(column, type)).append(" ");

        script.append(buildAICreateColumnCommentSql(column)).append(" ");
        return script.toString();
    }


    private String buildAutoIncrement(TableColumn column, DMColumnTypeEnum type) {
        if(!type.getColumnType().isSupportAutoIncrement()){
            return "";
        }
        if (column.getAutoIncrement() != null && column.getAutoIncrement()
                && column.getSeed() != null && column.getSeed() > 0 && column.getIncrement() != null && column.getIncrement() > 0) {
            return "IDENTITY(" + column.getSeed() + "," + column.getIncrement() + ")";
        }
        if (column.getAutoIncrement() != null && column.getAutoIncrement()) {
            return "IDENTITY(1,1)";
        }
        return "";
    }

    private String buildNullable(TableColumn column, DMColumnTypeEnum type) {
        if (!type.getColumnType().isSupportNullable()) {
            return "";
        }
        if (column.getNullable() != null && 1 == column.getNullable()) {
            return "NULL";
        } else {
            return "NOT NULL";
        }
    }

    private String buildDefaultValue(TableColumn column, DMColumnTypeEnum type) {
        if (!type.getColumnType().isSupportDefaultValue() || StringUtils.isEmpty(column.getDefaultValue())) {
            return "";
        }

        if ("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT ''");
        }

        if ("NULL".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT NULL");
        }

        return StringUtils.join("DEFAULT ", DMSqlGuards.requireDefaultExpression(column.getDefaultValue()));
    }

    private String buildDataType(TableColumn column, DMColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();
        if (Arrays.asList(CHAR, VARCHAR, VARCHAR2, LONGVARCHAR,TEXT,TIMESTAMP).contains(type)) {
            StringBuilder script = new StringBuilder();
            script.append(columnType);
            if (column.getColumnSize() != null && StringUtils.isEmpty(column.getUnit())) {
                script.append("(").append(column.getColumnSize()).append(")");
            } else if (column.getColumnSize() != null && !StringUtils.isEmpty(column.getUnit())) {
                script.append("(").append(column.getColumnSize()).append(" ")
                        .append(DMSqlGuards.requireUnit(column.getUnit())).append(")");
            }
            return script.toString();
        }

        if (Arrays.asList(DECIMAL, DEC, FLOAT, NUMBER, NUMERIC).contains(type)) {
            StringBuilder script = new StringBuilder();
            script.append(columnType);
            if (column.getColumnSize() != null && column.getDecimalDigits() == null) {
                script.append("(").append(column.getColumnSize()).append(")");
            } else if (column.getColumnSize() != null && column.getDecimalDigits() != null) {
                script.append("(").append(column.getColumnSize()).append(",").append(column.getDecimalDigits()).append(")");
            }
            return script.toString();
        }

        if (Arrays.asList(TIMESTAMP_WITH_TIME_ZONE).contains(type)) {
            StringBuilder script = new StringBuilder();
            if (column.getColumnSize() == null) {
                script.append(columnType);
            } else {
                String[] split = columnType.split("TIMESTAMP");
                script.append("TIMESTAMP").append("(").append(column.getColumnSize()).append(")").append(split[1]);
            }
            return script.toString();
        }

        if (Arrays.asList(INTERVAL_DAY_TO_HOUR,
                INTERVAL_DAY_TO_MINUTE, INTERVAL_DAY_TO_SECOND,
                INTERVAL_HOUR_TO_MINUTE,
                INTERVAL_HOUR_TO_SECOND,
                INTERVAL_MINUTE_TO_SECOND,
                INTERVAL_YEAR_TO_MONTH).contains(type)) {
            StringBuilder script = new StringBuilder();
            if (column.getColumnSize() == null) {
                script.append(columnType);
            } else {
                String[] split = columnType.split(" ");
                if (split.length == 4) {
                    script.append(split[0]).append(" ").append(split[1]).append(" (").append(column.getColumnSize()).append(") ").append(split[2]).append(" ").append(split[3]);
                }
            }
            return script.toString();
        }

        return columnType;
    }


    @Override
    public String buildModifyColumn(TableColumn tableColumn) {

        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            script.append(SQL_ALTER_TABLE).append(qualifiedTableName(tableColumn));
            script.append(" ").append(SQL_DROP_COLUMN)
                    .append(DMIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getName()));
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
            if (!StringUtils.equals(tableColumn.getOldName(), tableColumn.getName())) {
                script.append(SQL_ALTER_TABLE).append(qualifiedTableName(tableColumn));
                script.append(" ").append(SQL_RENAME_COLUMN)
                        .append(DMIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getOldName()))
                        .append(" TO ")
                        .append(DMIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getName()));
                script.append(";\n");

            }
            script.append(SQL_ALTER_TABLE).append(qualifiedTableName(tableColumn));
            script.append(" ").append("MODIFY (").append(buildCreateColumnSql(tableColumn)).append(") \n");

            return script.toString();

        }
        return "";
    }

    private static String buildUnknownColumnSql(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(DMIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName()))
                .append(" ")
                .append(DMSqlGuards.requireColumnTypeExpression(column.getColumnType()));
        if (StringUtils.isNotEmpty(column.getDefaultValue())) {
            String defaultValue = column.getDefaultValue();
            if ("EMPTY_STRING".equalsIgnoreCase(defaultValue.trim())) {
                script.append(" DEFAULT ''");
            } else if ("NULL".equalsIgnoreCase(defaultValue.trim())) {
                script.append(" DEFAULT NULL");
            } else {
                script.append(" DEFAULT ").append(DMSqlGuards.requireDefaultExpression(defaultValue));
            }
        }
        if (column.getNullable() != null) {
            script.append(column.getNullable() == 1 ? " NULL" : " NOT NULL");
        }
        return script.toString();
    }

    private static String qualifiedTableName(TableColumn column) {
        String tableName = DMIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getTableName());
        if (StringUtils.isBlank(column.getSchemaName())) {
            return tableName;
        }
        return DMIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getSchemaName()) + "." + tableName;
    }

    public static List<ColumnType> getTypes() {
        return Arrays.stream(DMColumnTypeEnum.values()).map(columnTypeEnum ->
                columnTypeEnum.getColumnType()
        ).toList();
    }
}
