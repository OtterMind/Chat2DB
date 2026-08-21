package ai.chat2db.plugin.db2.enums.type;

import ai.chat2db.spi.IColumnBuilder;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.plugin.db2.Db2SqlGuards;
import ai.chat2db.plugin.db2.identifier.Db2IdentifierProcessor;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static ai.chat2db.plugin.db2.constant.DB2ColumnTypeEnumConstants.*;
public enum DB2ColumnTypeEnum implements IColumnBuilder {

    ANCHOR("ANCHOR", false, false, true, false, false, false, true, true, false, false),


    BIGINT("BIGINT", false, false, true, false, false, false, true, true, false, false),


    BINARY("BINARY", false, false, true, false, false, false, true, true, false, false),


    BLOB("BLOB", false, false, true, false, false, false, true, true, false, false),


    BOOLEAN("BOOLEAN", false, false, true, false, false, false, true, true, false, false),


    CHAR("CHAR", true, false, true, false, false, false, true, true, false, true),
    CHARACTER("CHARACTER", true, false, true, false, false, false, true, true, false, true),

    CLOB("CLOB", false, false, true, false, false, false, true, true, false, false),


    // COURSE removed — DB2 has no built-in type named COURSE; it was a stray/wrong
    // enum constant that would produce invalid DDL if a column mapped to it.


    DATE("DATE", false, false, true, false, false, false, true, true, false, false),


    DB2SECURITYLABEL("DB2SECURITYLABEL", false, false, true, false, false, false, true, true, false, false),


    DBCLOB("DBCLOB", false, false, true, false, false, false, true, true, false, false),


    DEC("DEC", true, true, true, false, false, false, true, true, false, false),

    DECFLOAT("DECFLOAT", false, false, true, false, false, false, true, true, false, false),

    DECIMAL("DECIMAL", true, true, true, false, false, false, true, true, false, false),

    DOUBLE("DOUBLE", false, false, true, false, false, false, true, true, false, false),


    FLOAT("FLOAT", true, false, true, false, false, false, true, true, false, false),


    GRAPHIC("GRAPHIC", false, false, true, false, false, false, true, true, false, false),

    INT("INT", false, false, true, false, false, false, true, true, false, false),

    INTEGER("INTEGER", false, false, true, false, false, false, true, true, false, false),


    LONG("LONG", false, false, true, false, false, false, true, true, false, false),


    NCHAR("NCHAR", true, false, true, false, false, false, true, true, false, true),

    NCLOB("NCLOB", false, false, true, false, false, false, true, true, false, false),

    NUM("NUM", true, true, true, false, false, false, true, true, false, false),

    NUMERIC("NUMERIC", true, true, true, false, false, false, true, true, false, false),


    NVARCHAR("NVARCHAR", true, false, true, false, false, false, true, true, false, true),


    REAL("REAL", false, false, true, false, false, false, true, true, false, false),


    REF("REF", false, false, true, false, false, false, true, true, false, false),


    SMALLINT("SMALLINT", false, false, true, false, false, false, true, true, false, false),


    TIME("TIME", false, false, true, false, false, false, true, true, false, false),


    TIMESTAMP("TIMESTAMP", false, false, true, false, false, false, true, true, false, false),


    VARBINARY("VARBINARY", false, false, true, false, false, false, true, true, false, false),


    VARCHAR("VARCHAR", true, false, true, false, false, false, true, true, false, true),

    XML("XML", false, false, true, false, false, false, true, true, false, false),
    ;




    private ColumnType columnType;

    public static DB2ColumnTypeEnum getByType(String dataType) {
        if (dataType == null) {
            return null;
        }
        String normalized = dataType.trim();
        int paren = normalized.indexOf('(');
        if (paren >= 0) {
            normalized = normalized.substring(0, paren);
        }
        return COLUMN_TYPE_MAP.get(normalized.toUpperCase());
    }

    private static Map<String, DB2ColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (DB2ColumnTypeEnum value : DB2ColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName(), value);
        }
    }

    public ColumnType getColumnType() {
        return columnType;
    }


    DB2ColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale, boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset, boolean supportCollation, boolean supportComments, boolean supportDefaultValue, boolean supportExtent, boolean supportUnit) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable, supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue, supportExtent, false, supportUnit);
    }

    @Override
    public String buildCreateColumnSql(TableColumn column) {
        DB2ColumnTypeEnum type = COLUMN_TYPE_MAP.get(column.getColumnType().toUpperCase());
        if (type == null) {
            return buildSafeFallbackColumn(column);
        }
        StringBuilder script = new StringBuilder();

        script.append("\"").append(Db2IdentifierProcessor.escapeIdentifier(column.getName())).append("\"").append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        script.append(buildNullable(column, type)).append(" ");

        return script.toString();
    }


    private String buildNullable(TableColumn column, DB2ColumnTypeEnum type) {
        if (!type.getColumnType().isSupportNullable()) {
            return "";
        }
        if (column.getNullable() != null && 1 == column.getNullable()) {
            return "NULL";
        } else {
            return "NOT NULL";
        }
    }

    /**
     * Fallback for column types that are not exact enum matches (e.g. {@code VARCHAR(10)}).
     * The type name is validated against a strict shape and the column name is escaped as a
     * delimited identifier, so neither value can break out of the column definition.
     */
    private static String buildSafeFallbackColumn(TableColumn column) {
        String columnType = Db2SqlGuards.requireColumnTypeExpression(column.getColumnType());
        return "\"" + Db2IdentifierProcessor.escapeIdentifier(column.getName()) + "\" " + columnType;
    }

    private String buildDefaultValue(TableColumn column, DB2ColumnTypeEnum type) {
        if (!type.getColumnType().isSupportDefaultValue() || StringUtils.isEmpty(column.getDefaultValue())) {
            return "";
        }

        if ("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT ''");
        }

        if ("NULL".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT NULL");
        }

        return StringUtils.join("DEFAULT ", Db2SqlGuards.requireDefaultExpression(column.getDefaultValue()));
    }

    private String buildDataType(TableColumn column, DB2ColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();
        if (Arrays.asList(CHAR, VARCHAR,NCHAR,CHARACTER,NVARCHAR).contains(type)) {
            StringBuilder script = new StringBuilder();
            script.append(columnType);
            if (column.getColumnSize() != null && StringUtils.isEmpty(column.getUnit())) {
                script.append("(").append(column.getColumnSize()).append(")");
            } else if (column.getColumnSize() != null && !StringUtils.isEmpty(column.getUnit())) {
                script.append("(").append(column.getColumnSize()).append(" ").append(Db2SqlGuards.requireUnit(column.getUnit())).append(")");
            }
            return script.toString();
        }

        if (Arrays.asList(DEC,DECIMAL, FLOAT, NUM, TIMESTAMP, NUMERIC).contains(type)) {
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
            script.append(SQL_ALTER_TABLE).append("\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getSchemaName())).append("\".\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getTableName())).append("\"");
            script.append(" ").append(SQL_DROP_COLUMN).append("\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getName())).append("\"");
            return script.toString();
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            script.append(SQL_ALTER_TABLE).append("\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getSchemaName())).append("\".\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getTableName())).append("\"");
            script.append(" ").append("ADD (").append(buildCreateColumnSql(tableColumn)).append(")");
            return script.toString();
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            script.append(SQL_ALTER_TABLE).append("\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getSchemaName())).append("\".\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getTableName())).append("\"");
            script.append(" ").append("MODIFY (").append(buildCreateColumnSql(tableColumn)).append(") \n");

            if (!StringUtils.equalsIgnoreCase(tableColumn.getOldName(), tableColumn.getName())) {
                script.append(";");
                script.append(SQL_ALTER_TABLE).append("\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getSchemaName())).append("\".\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getTableName())).append("\"");
                script.append(" ").append(SQL_RENAME_COLUMN).append("\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getOldName())).append("\"").append(" TO ").append("\"").append(Db2IdentifierProcessor.escapeIdentifier(tableColumn.getName())).append("\"");

            }
            return script.toString();

        }
        return "";
    }

    public static List<ColumnType> getTypes() {
        return Arrays.stream(DB2ColumnTypeEnum.values()).map(columnTypeEnum ->
                columnTypeEnum.getColumnType()
        ).toList();
    }
}
