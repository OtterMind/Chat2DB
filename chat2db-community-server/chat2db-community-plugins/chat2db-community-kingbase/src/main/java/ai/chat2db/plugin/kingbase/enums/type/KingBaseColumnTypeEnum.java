package ai.chat2db.plugin.kingbase.enums.type;

import ai.chat2db.spi.IColumnBuilder;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.plugin.kingbase.KingBaseSqlGuards;
import ai.chat2db.plugin.kingbase.identifier.KingBaseSQLIdentifierProcessor;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.chat2db.plugin.kingbase.constant.KingBaseColumnTypeEnumConstants.*;
public enum KingBaseColumnTypeEnum implements IColumnBuilder {

    BIGSERIAL("BIGSERIAL", false, false, true, false, false, false, true, true, false, false),
    BIT("BIT", true, false, true, false, false, false, true, true, false, false),
    BOOL("BOOL", false, false, true, false, false, false, true, true, false, false),
    BOX("BOX", false, false, true, false, false, false, true, true, false, false),
    BYTEA("BYTEA", false, false, true, false, false, false, true, true, false, false),

    CHARACTER("CHARACTER", true, false, true, false, false, true, true, true, false, false),

    CHARACTER_VARYING("CHARACTER VARYING", true, false, true, false, false, true, true, true, false, false),
    CHAR("CHAR", true, false, true, false, false, true, true, true, false, false),

    CID("CID", false, false, true, false, false, false, true, true, false, false),
    CIDR("CIDR", false, false, true, false, false, false, true, true, false, false),

    CIRCLE("CIRCLE", false, false, true, false, false, false, true, true, false, false),

    CLOB("CLOB", false, false, true, false, false, false, true, true, false, false),
    DATE("DATE", false, false, true, false, false, false, true, true, false, false),
    DECIMAL("DECIMAL", true, true, true, false, false, false, true, true, false, false),
    FLOAT4("FLOAT4", false, false, true, false, false, false, true, true, false, false),
    FLOAT8("FLOAT8", false, false, true, false, false, false, true, true, false, false),

    INTEGER("INTEGER", false, false, true, false, false, false, true, true, false, false),
    INET("INET", false, false, true, false, false, false, true, true, false, false),
    INT2("INT2", false, false, true, false, false, false, true, true, false, false),
    INT4("INT4", false, false, true, false, false, false, true, true, false, false),
    INT8("INT8", false, false, true, false, false, false, true, true, false, false),
    INTERVAL("INTERVAL", false, false, true, false, false, false, true, true, false, false),
    JSON("JSON", false, false, true, false, false, false, true, true, false, false),
    JSONB("JSONB", false, false, true, false, false, false, true, true, false, false),
    LINE("LINE", false, false, true, false, false, false, true, true, false, false),
    LSEG("LSEG", false, false, true, false, false, false, true, true, false, false),
    MACADDR("MACADDR", false, false, true, false, false, false, true, true, false, false),
    MONEY("MONEY", false, false, true, false, false, false, true, true, false, false),
    NUMERIC("NUMERIC", true, true, true, false, false, false, true, true, false, false),
    PATH("PATH", false, false, true, false, false, false, true, true, false, false),
    POINT("POINT", false, false, true, false, false, false, true, true, false, false),
    POLYGON("POLYGON", false, false, true, false, false, false, true, true, false, false),
    SERIAL("SERIAL", false, false, true, false, false, false, true, true, false, false),
    SERIAL2("SERIAL2", false, false, true, false, false, false, true, true, false, false),
    SERIAL4("SERIAL4", false, false, true, false, false, false, true, true, false, false),
    SERIAL8("SERIAL8", false, false, true, false, false, false, true, true, false, false),
    SMALLSERIAL("SMALLSERIAL", false, false, true, false, false, false, true, true, false, false),
    TEXT("TEXT", false, false, true, false, false, true, true, true, false, false),
    TIME("TIME", true, false, true, false, false, false, true, true, false, false),
    TIMESTAMP("TIMESTAMP", true, false, true, false, false, false, true, true, false, false),
    TIMESTAMPTZ("TIMESTAMPTZ", true, false, true, false, false, false, true, true, false, false),
    TIMETZ("TIMETZ", true, false, true, false, false, false, true, true, false, false),
    TSQUERY("TSQUERY", false, false, true, false, false, false, true, true, false, false),
    TSVECTOR("TSVECTOR", false, false, true, false, false, false, true, true, false, false),
    TXID_SNAPSHOT("TXID_SNAPSHOT", false, false, true, false, false, false, true, true, false, false),
    UUID("UUID", false, false, true, false, false, false, true, true, false, false),
    VARBIT("VARBIT", true, false, true, false, false, false, true, true, false, false),
    VARCHAR("VARCHAR", true, false, true, false, false, true, true, true, false, false),
    XML("XML", false, false, true, false, false, false, true, true, false, false),

    ;





    private static final Map<String, KingBaseColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (KingBaseColumnTypeEnum value : KingBaseColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName().toUpperCase(Locale.ROOT), value);
        }
    }

    private ColumnType columnType;


    KingBaseColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale, boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset, boolean supportCollation, boolean supportComments, boolean supportDefaultValue, boolean supportExtent, boolean supportValue) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable, supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue, supportExtent, supportValue, false);
    }

    public static KingBaseColumnTypeEnum getByType(String dataType) {
        if (StringUtils.isBlank(dataType)) {
            return null;
        }
        String typeExpression = KingBaseSqlGuards.requireColumnTypeExpression(dataType);
        String baseType = typeExpression;
        int argumentsStart = baseType.indexOf('(');
        if (argumentsStart >= 0) {
            baseType = baseType.substring(0, argumentsStart);
        }
        while (baseType.stripTrailing().endsWith("[]")) {
            baseType = baseType.stripTrailing();
            baseType = baseType.substring(0, baseType.length() - 2);
        }
        return COLUMN_TYPE_MAP.get(baseType.trim().toUpperCase(Locale.ROOT));
    }

    public static List<ColumnType> getTypes() {
        return Arrays.stream(KingBaseColumnTypeEnum.values()).map(columnTypeEnum ->
                columnTypeEnum.getColumnType()
        ).toList();
    }

    public ColumnType getColumnType() {
        return columnType;
    }

    public static String buildCreateColumnSqlSafely(TableColumn column) {
        KingBaseColumnTypeEnum type = getByType(column.getColumnType());
        return type == null ? buildSafeFallbackColumn(column) : type.buildCreateColumnSql(column);
    }

    public static String buildModifyColumnSafely(TableColumn column) {
        KingBaseColumnTypeEnum type = getByType(column.getColumnType());
        if (type != null) {
            return type.buildModifyColumn(column);
        }
        if (EditStatusEnum.DELETE.name().equals(column.getEditStatus())) {
            return SQL_DROP_COLUMN + KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName());
        }
        if (EditStatusEnum.ADD.name().equals(column.getEditStatus())) {
            return "ADD COLUMN " + buildSafeFallbackColumn(column);
        }
        if (EditStatusEnum.MODIFY.name().equals(column.getEditStatus())) {
            String columnName = KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName());
            String dataType = KingBaseSqlGuards.requireColumnTypeExpression(column.getColumnType());
            return SQL_ALTER_COLUMN + columnName + " TYPE " + dataType
                    + " USING " + columnName + "::" + dataType;
        }
        return "";
    }

    @Override
    public String buildCreateColumnSql(TableColumn column) {
        KingBaseColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildSafeFallbackColumn(column);
        }
        StringBuilder script = new StringBuilder();

        script.append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");

        script.append(buildDataType(column, type)).append(" ");


        script.append(buildCollation(column, type)).append(" ");

        script.append(buildNullable(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        return script.toString();
    }

    private String buildCollation(TableColumn column, KingBaseColumnTypeEnum type) {
        if (!type.getColumnType().isSupportCollation() || StringUtils.isEmpty(column.getCollationName())) {
            return "";
        }
        return KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getCollationName());
    }

    @Override
    public String buildModifyColumn(TableColumn column) {

        if (EditStatusEnum.DELETE.name().equals(column.getEditStatus())) {
            return StringUtils.join(SQL_DROP_COLUMN, KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName()));
        }
        if (EditStatusEnum.ADD.name().equals(column.getEditStatus())) {
            return StringUtils.join("ADD COLUMN ", buildCreateColumnSql(column));
        }
        if (EditStatusEnum.MODIFY.name().equals(column.getEditStatus())) {
            StringBuilder script = new StringBuilder();
            script.append(SQL_ALTER_COLUMN).append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" TYPE ").append(buildDataType(column, this)).append(",\n");
            if (column.getNullable() != null && 1 == column.getNullable()) {
                script.append("\t").append(SQL_ALTER_COLUMN).append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" DROP NOT NULL ,\n");
            } else {
                script.append("\t").append(SQL_ALTER_COLUMN).append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" SET NOT NULL ,\n");

            }
            String defaultValue = buildDefaultValue(column, this);
            if (StringUtils.isNotBlank(defaultValue)) {
                script.append(SQL_ALTER_COLUMN).append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" SET ").append(defaultValue).append(",\n");
            }
            script = new StringBuilder(script.substring(0, script.length() - 2));
            return script.toString();
        }
        return "";
    }

    public String buildComment(TableColumn column, KingBaseColumnTypeEnum type) {
        if (!this.columnType.isSupportComments() || column.getComment() == null
                || EditStatusEnum.DELETE.name().equals(column.getEditStatus())) {
            return "";
        }
        return StringUtils.join(SQL_COMMENT_COLUMN, " ", qualifiedName(column.getSchemaName(), column.getTableName()),
                ".", KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName()), " IS '", KingBaseSQLIdentifierProcessor.INSTANCE.escapeString(column.getComment()), "';");
    }

    private static String qualifiedName(String schemaName, String objectName) {
        String quotedName = KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(objectName);
        if (StringUtils.isBlank(schemaName)) {
            return quotedName;
        }
        return KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(schemaName) + "." + quotedName;
    }

    private String buildDefaultValue(TableColumn column, KingBaseColumnTypeEnum type) {
        if (!type.getColumnType().isSupportDefaultValue() || StringUtils.isEmpty(column.getDefaultValue())) {
            return "";
        }

        if("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())){
            return StringUtils.join("DEFAULT ''");
        }

        if("NULL".equalsIgnoreCase(column.getDefaultValue().trim())){
            return StringUtils.join("DEFAULT NULL");
        }

        if (Arrays.asList(CHAR, VARCHAR).contains(type)) {
            if (KingBaseSqlGuards.isFunctionOrCastExpression(column.getDefaultValue())) {
                return StringUtils.join("DEFAULT ",
                        KingBaseSqlGuards.requireDefaultExpression(column.getDefaultValue()));
            }
            return StringUtils.join("DEFAULT '", KingBaseSQLIdentifierProcessor.INSTANCE.escapeString(column.getDefaultValue()), "'");
        }

        if (Arrays.asList(TIMESTAMP, TIME, TIMETZ, TIMESTAMPTZ, DATE).contains(type)) {
            if (KingBaseSqlGuards.isTemporalExpression(column.getDefaultValue())) {
                return StringUtils.join("DEFAULT ",
                        KingBaseSqlGuards.requireDefaultExpression(column.getDefaultValue()));
            }
            return StringUtils.join("DEFAULT '", KingBaseSQLIdentifierProcessor.INSTANCE.escapeString(column.getDefaultValue()), "'");
        }

        return StringUtils.join("DEFAULT ", KingBaseSqlGuards.requireDefaultExpression(column.getDefaultValue()));
    }

    private static String buildSafeFallbackColumn(TableColumn column) {
        StringBuilder script = new StringBuilder();
        script.append(KingBaseSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName()))
                .append(" ")
                .append(KingBaseSqlGuards.requireColumnTypeExpression(column.getColumnType()));
        if (column.getNullable() != null) {
            script.append(column.getNullable() == 1 ? " NULL" : " NOT NULL");
        }
        if (StringUtils.isNotEmpty(column.getDefaultValue())) {
            if ("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())) {
                script.append(" DEFAULT ''");
            } else {
                script.append(" DEFAULT ")
                        .append(KingBaseSqlGuards.requireDefaultExpression(column.getDefaultValue()));
            }
        }
        return script.toString();
    }

    private String buildNullable(TableColumn column, KingBaseColumnTypeEnum type) {
        if (!type.getColumnType().isSupportNullable()) {
            return "";
        }
        if (column.getNullable() != null && 1 == column.getNullable()) {
            return "NULL";
        } else {
            return "NOT NULL";
        }
    }

    private String buildDataType(TableColumn column, KingBaseColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();
        if (Arrays.asList(VARCHAR, CHAR,CHARACTER).contains(type)) {
            if (column.getColumnSize() == null ) {
                return columnType;
            }
            return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
        }

        if (Arrays.asList(VARBIT, BIT).contains(type)) {
            if (column.getColumnSize() == null ) {
                return columnType;
            }
            return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
        }

        if (Arrays.asList(TIME, TIMETZ, TIMESTAMPTZ, TIMESTAMP).contains(type)) {
            if (column.getColumnSize() == null || column.getColumnSize() == 0) {
                return columnType;
            } else {
                return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
            }
        }

        if (Arrays.asList(DECIMAL, NUMERIC).contains(type)) {
            if (column.getColumnSize() == null && column.getDecimalDigits() == null) {
                return columnType;
            }
            if (column.getColumnSize() != null && column.getDecimalDigits() == null) {
                return StringUtils.join(columnType, "(", column.getColumnSize() + ")");
            } else {
                return StringUtils.join(columnType, "(", column.getColumnSize() + "," + column.getDecimalDigits() + ")");
            }
        }
        return columnType;
    }

}
