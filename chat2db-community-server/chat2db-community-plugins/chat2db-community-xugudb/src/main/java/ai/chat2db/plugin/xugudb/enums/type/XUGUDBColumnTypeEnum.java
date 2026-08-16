package ai.chat2db.plugin.xugudb.enums.type;

import ai.chat2db.spi.IColumnBuilder;
import ai.chat2db.plugin.xugudb.XugudbSqlGuards;
import ai.chat2db.plugin.xugudb.identifier.XugudbIdentifierProcessor;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

import static ai.chat2db.plugin.xugudb.constant.XUGUDBColumnTypeEnumConstants.*;
public enum XUGUDBColumnTypeEnum implements IColumnBuilder {

    TINYINT("TINYINT", false, false, true, false, false, false, true, true, false, false),

    BIGINT("BIGINT", false, false, true, false, false, false, true, true, false, false),

    SMALLINT("SMALLINT", false, false, true, false, false, false, true, true, false, false),

    INTEGER("INTEGER", false, false, true, false, false, false, true, true, false, false),

    NUMERIC("NUMERIC", true, true, true, false, false, false, true, true, false, false),

    FLOAT("FLOAT", true, false, true, false, false, false, true, true, false, false),

    DOUBLE("DOUBLE", false, false, true, false, false, false, true, true, false, false),

    CHAR("CHAR", true, false, true, false, false, false, true, true, false, true),

    NCHAR("NCHAR", true, false, true, false, false, false, true, true, false, true),

    VARCHAR("VARCHAR", true, false, true, false, false, false, true, true, false, true),

    VARCHAR2("VARCHAR2", true, false, true, false, false, false, true, true, false, true),

    DATE("DATE", false, false, true, false, false, false, true, true, false, false),

    TIME("TIME", false, false, true, false, false, false, true, true, false, false),

    TIME_WITH_TIME_ZONE("TIME WITH TIME ZONE", false, false, true, false, false, false, true, true, false, false),

    DATETIME("DATETIME", false, false, true, false, false, false, true, true, false, false),

    DATETIME_WITH_TIME_ZONE("DATETIME WITH TIME ZONE", false, false, true, false, false, false, true, true, false, false),

    TIMESTAMP("TIMESTAMP", false, false, true, false, false, false, true, true, false, false),

    TIMESTAMP_WITH_TIME_ZONE("TIMESTAMP WITH TIME ZONE", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_YEAR("INTERVAL YEAR", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_MONTH("INTERVAL MONTH", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_DAY("INTERVAL DAY", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_HOUR("INTERVAL HOUR", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_MINUTE("INTERVAL MINUTE", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_SECOND("INTERVAL SECOND", false, false, true, false, false, false, true, true, false, false),

    INTERVAL_YEAR_TO_MONTH("INTERVAL YEAR TO MONTH", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_DAY_TO_HOUR("INTERVAL DAY TO HOUR", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_DAY_TO_MINUTE("INTERVAL DAY TO MINUTE", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_DAY_TO_SECOND("INTERVAL DAY TO SECOND", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_HOUR_TO_MINUTE("INTERVAL HOUR TO MINUTE", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_HOUR_TO_SECOND("INTERVAL HOUR TO SECOND", true, false, true, false, false, false, true, true, false, false),

    INTERVAL_MINUTE_TO_SECOND("INTERVAL MINUTE TO SECOND", true, false, true, false, false, false, true, true, false, false),

    BLOB("BLOB", false, false, true, false, false, false, true, true, false, false),

    CLOB("CLOB", false, false, true, false, false, false, true, true, false, false),

    BOOLEAN("BOOLEAN", false, false, true, false, false, false, true, true, false, false),

    BINARY("BINARY", false, false, true, false, false, false, true, true, false, false),

    OBJECT("OBJECT", false, false, true, false, false, false, true, true, false, false),

    VARRAY("VARRAY", false, false, true, false, false, false, true, true, false, false),
    ;




    private ColumnType columnType;

    public static XUGUDBColumnTypeEnum getByType(String dataType) {
        if (dataType == null) {
            return null;
        }
        return COLUMN_TYPE_MAP.get(dataType.trim().toUpperCase(Locale.ROOT));
    }

    private static Map<String, XUGUDBColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (XUGUDBColumnTypeEnum value : XUGUDBColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName(), value);
        }
    }

    public ColumnType getColumnType() {
        return columnType;
    }


    XUGUDBColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale, boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset, boolean supportCollation, boolean supportComments, boolean supportDefaultValue, boolean supportExtent, boolean supportUnit) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable, supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue, supportExtent, false, supportUnit);
    }

    @Override
    public String buildCreateColumnSql(TableColumn column) {
        XUGUDBColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildFallbackColumn(column);
        }
        StringBuilder script = new StringBuilder();

        script.append("\"").append(XugudbIdentifierProcessor.escapeIdentifier(column.getName())).append("\"").append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        script.append(buildAutoIncrement(column, type)).append(" ");

        script.append(buildNullable(column, type)).append(" ");

        return script.toString();
    }

    public String buildUpdateColumnSql(TableColumn column) {
        XUGUDBColumnTypeEnum type = getByType(column.getColumnType());
        StringBuilder script = new StringBuilder();
        script.append(SQL_ALTER_TABLE).append("\"").append(XugudbIdentifierProcessor.escapeIdentifier(column.getSchemaName())).append("\".\"").append(XugudbIdentifierProcessor.escapeIdentifier(column.getTableName())).append("\"");
        script.append(" ").append("MODIFY (").append("\"").append(XugudbIdentifierProcessor.escapeIdentifier(column.getName())).append("\"").append(" ");
        boolean isModify = false;
        Integer oldColumnSize = Optional.ofNullable(column.getOldColumn())
                .map(TableColumn::getColumnSize)
                .orElse(null);

        Integer newColumnSize = Optional.ofNullable(column.getColumnSize())
                .orElse(null);
        if (!Objects.equals(column.getOldColumn().getColumnType(), column.getColumnType())
                || !Objects.equals(oldColumnSize, newColumnSize)) {
            script.append(type == null
                    ? XugudbSqlGuards.requireColumnTypeExpression(column.getColumnType())
                    : buildDataType(column, type)).append(" ");
            isModify = true;
        }
        if (!Objects.equals(column.getOldColumn().getNullable(), column.getNullable())) {
            script.append(type == null ? buildFallbackNullable(column) : buildNullable(column, type)).append(" ");
            isModify = true;
        }
        script.append(") \n");

        return isModify ? script.toString() : "";
    }

    private static String buildFallbackColumn(TableColumn column) {
        String columnType = XugudbSqlGuards.requireColumnTypeExpression(column.getColumnType());
        return XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName()) + " " + columnType;
    }

    private static String buildFallbackNullable(TableColumn column) {
        return column.getNullable() != null && column.getNullable() == 1 ? "DROP NOT NULL" : "SET NOT NULL";
    }

    private String buildAutoIncrement(TableColumn column, XUGUDBColumnTypeEnum type) {
        if (!type.getColumnType().isSupportAutoIncrement()) {
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

    private String buildNullable(TableColumn column, XUGUDBColumnTypeEnum type) {
        if (!type.getColumnType().isSupportNullable()) {
            return "";
        }
        if (column.getNullable() != null && 1 == column.getNullable()) {
            if (EditStatusEnum.MODIFY.name().equals(column.getEditStatus())) {
                return "DROP NOT NULL";
            } else {
                return "NULL";
            }
        } else {
            if (EditStatusEnum.MODIFY.name().equals(column.getEditStatus())) {
                return "SET NOT NULL";
            } else {
                return "NOT NULL";
            }
        }
    }

    private String buildDefaultValue(TableColumn column, XUGUDBColumnTypeEnum type) {
        if (!type.getColumnType().isSupportDefaultValue() || StringUtils.isEmpty(column.getDefaultValue())) {
            return "";
        }

        if ("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT ''");
        }

        if ("NULL".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT NULL");
        }

        return StringUtils.join("DEFAULT ", XugudbSqlGuards.requireDefaultValue(column.getDefaultValue()));
    }

    private String buildDataType(TableColumn column, XUGUDBColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();
        if (Arrays.asList(CHAR, VARCHAR, VARCHAR2).contains(type)) {
            StringBuilder script = new StringBuilder();
            script.append(columnType);
            if (column.getColumnSize() != null && StringUtils.isEmpty(column.getUnit())) {
                script.append("(").append(column.getColumnSize()).append(")");
            } else if (column.getColumnSize() != null && !StringUtils.isEmpty(column.getUnit())) {
                script.append("(").append(column.getColumnSize()).append(" ").append(XugudbSqlGuards.requireUnit(column.getUnit())).append(")");
            }
            return script.toString();
        }

        if (Arrays.asList(FLOAT, TIMESTAMP).contains(type)) {
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

        if (Arrays.asList(NUMERIC).contains(type)) {
            StringBuilder script = new StringBuilder();
            script.append(columnType);
            if (column.getColumnSize() != null && column.getDecimalDigits() == null) {
                script.append("(").append(column.getColumnSize()).append(")");
            } else if (column.getColumnSize() != null && column.getDecimalDigits() != null) {
                script.append("(").append(column.getColumnSize()).append(",").append(column.getDecimalDigits()).append(")");
            }
            return script.toString();
        }

        return columnType;
    }


    @Override
    public String buildModifyColumn(TableColumn tableColumn) {

        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            script.append(SQL_ALTER_TABLE).append("\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableColumn.getSchemaName())).append("\".\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableColumn.getTableName())).append("\"");
            script.append(" ").append(SQL_DROP_COLUMN).append("\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableColumn.getName())).append("\"");
            return script.toString();
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            script.append(SQL_ALTER_TABLE).append("\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableColumn.getSchemaName())).append("\".\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableColumn.getTableName())).append("\"");
            script.append(" ").append("ADD (").append(buildCreateColumnSql(tableColumn)).append(")");
            return script.toString();
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            if (!StringUtils.equals(tableColumn.getOldName(), tableColumn.getName())) {
                script.append(SQL_ALTER_TABLE).append("\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableColumn.getSchemaName())).append("\".\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableColumn.getTableName())).append("\"");
                script.append(" ").append(SQL_RENAME_COLUMN).append("\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableColumn.getOldName())).append("\"").append(" TO ").append("\"").append(XugudbIdentifierProcessor.escapeIdentifier(tableColumn.getName())).append("\" ").append(";\n").append(buildUpdateColumnSql(tableColumn));
            } else {
                script.append(buildUpdateColumnSql(tableColumn)).append("\n");
            }

            return script.toString();

        }
        return "";
    }

    public static List<ColumnType> getTypes() {
        return Arrays.stream(XUGUDBColumnTypeEnum.values()).map(columnTypeEnum ->
                columnTypeEnum.getColumnType()
        ).toList();
    }
}
