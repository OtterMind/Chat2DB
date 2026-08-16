package ai.chat2db.plugin.hive.enums.type;

import ai.chat2db.plugin.hive.HiveSqlGuards;
import ai.chat2db.plugin.hive.identifier.HiveIdentifierProcessor;
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

import static ai.chat2db.plugin.hive.constant.HiveColumnTypeEnumConstants.*;
public enum HiveColumnTypeEnum implements IColumnBuilder {
    TINYINT("TINYINT", true, false, true, true, false, false, true, true, false, false),

    SMALLINT("SMALLINT", false, false, true, true, false, false, true, true, false, false),

    INT("INT", false, false, true, true, false, false, true, true, false, false),

    INTEGER("INTEGER", false, false, true, true, false, false, true, true, false, false),

    BIGINT("BIGINT", false, false, true, true, false, false, true, true, false, false),

    FLOAT("FLOAT", true, true, true, false, false, false, true, true, false, false),

    DOUBLE("DOUBLE", true, true, true, false, false, false, true, true, false, false),

    DECIMAL("DECIMAL", true, true, true, false, false, false, true, true, false, false),
    NUMERIC("NUMERIC", true, true, true, false, false, false, true, true, false, false),
    TIMESTAMP("TIMESTAMP", true, false, true, false, false, false, true, true, true, false),

    DATE("DATE", false, false, true, false, false, false, true, true, false, false),

    INTERVAL("INTERVAL", true, false, true, false, false, false, true, true, true, false),
    STRING("STRING", true, false, true, false, true, true, true, true, false, false),

    CHAR("CHAR", true, false, true, false, true, true, true, true, false, false),

    VARCHAR("VARCHAR", true, false, true, false, true, true, true, true, false, false),
    BOOLEAN("BOOLEAN", false, false, true, true, false, false, true, true, false, false),

    BINARY("BINARY", true, false, true, false, false, false, true, true, false, false),
    ARRAY("ARRAY", true, false, true, false, true, true, true, true, false, false),

    MAP("MAP", true, false, true, false, true, true, true, true, false, false),

    STRUCT("STRUCT", true, false, true, false, true, true, true, true, false, false),

    UNIONTYPE("UNIONTYPE", true, false, true, false, true, true, true, true, false, false),


    ;




    private ColumnType columnType;

    public static HiveColumnTypeEnum getByType(String dataType) {
        return dataType == null ? null : COLUMN_TYPE_MAP.get(dataType.trim().toUpperCase(Locale.ROOT));
    }

    public static String buildCreateColumnSqlSafely(TableColumn column) {
        HiveColumnTypeEnum type = getByType(column.getColumnType());
        return type == null ? buildUnknownColumn(column) : type.buildCreateColumnSql(column);
    }

    public static String buildModifyColumnSafely(TableColumn column) {
        HiveColumnTypeEnum type = getByType(column.getColumnType());
        return (type == null ? INT : type).buildModifyColumn(column);
    }

    public ColumnType getColumnType() {
        return columnType;
    }


    HiveColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale, boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset, boolean supportCollation, boolean supportComments, boolean supportDefaultValue, boolean supportExtent, boolean supportValue) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable, supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue, supportExtent,supportValue,false);
    }

    private static Map<String, HiveColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (HiveColumnTypeEnum value : HiveColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName(), value);
        }
    }


    @Override
    public String buildCreateColumnSql(TableColumn column) {
        HiveColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildUnknownColumn(column);
        }
        StringBuilder script = new StringBuilder();

        script.append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildCharset(column,type)).append(" ");

        script.append(buildCollation(column,type)).append(" ");

        if(!EditStatusEnum.ADD.name().equals(column.getEditStatus())) {
            script.append(buildNullable(column,type)).append(" ");
        }

        script.append(buildComment(column,type)).append(" ");

        return script.toString();
    }

    private String buildCharset(TableColumn column, HiveColumnTypeEnum type) {
        if(!type.getColumnType().isSupportCharset() || StringUtils.isEmpty(column.getCharSetName())){
            return "";
        }
        return StringUtils.join("CHARACTER SET ", HiveSqlGuards.requireHiveName(column.getCharSetName(), "charset"));
    }

    private String buildCollation(TableColumn column, HiveColumnTypeEnum type) {
        if(!type.getColumnType().isSupportCollation() || StringUtils.isEmpty(column.getCollationName())){
            return "";
        }
        return StringUtils.join("COLLATE ", HiveSqlGuards.requireHiveName(column.getCollationName(), "collation"));
    }

    @Override
    public String buildModifyColumn(TableColumn tableColumn) {

        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            return StringUtils.join("DROP COLUMN ", HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getName()));
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            return StringUtils.join("ADD COLUMNS (", buildCreateColumnSql(tableColumn), ")");
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            if (!StringUtils.equalsIgnoreCase(tableColumn.getOldName(), tableColumn.getName())) {
                return StringUtils.join("CHANGE COLUMN ", HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getOldName()), " ", buildCreateColumnSql(tableColumn));
            } else {
                return StringUtils.join("CHANGE ", HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableColumn.getOldName()), " ", buildCreateColumnSql(tableColumn));
            }
        }
        return "";
    }

    private String buildAutoIncrement(TableColumn column, HiveColumnTypeEnum type) {
        if(!type.getColumnType().isSupportAutoIncrement()){
            return "";
        }
        if (column.getAutoIncrement() != null && column.getAutoIncrement()) {
            return "AUTO_INCREMENT";
        }
        return "";
    }

    private String buildComment(TableColumn column, HiveColumnTypeEnum type) {
        if(!type.columnType.isSupportComments() || StringUtils.isEmpty(column.getComment())){
            return "";
        }
        return StringUtils.join(SQL_COMMENT, HiveIdentifierProcessor.INSTANCE.escapeString(column.getComment()),"'");
    }

    private String buildExt(TableColumn column, HiveColumnTypeEnum type) {
        if(!type.columnType.isSupportExtent() || StringUtils.isEmpty(column.getExtent())){
            return "";
        }
        return column.getComment();
    }

    private String buildDefaultValue(TableColumn column, HiveColumnTypeEnum type) {
        if(!type.getColumnType().isSupportDefaultValue() || StringUtils.isEmpty(column.getDefaultValue())){
            return "";
        }

        if("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())){
            return StringUtils.join("DEFAULT ''");
        }

        if("NULL".equalsIgnoreCase(column.getDefaultValue().trim())){
            return StringUtils.join("DEFAULT NULL");
        }

        if(Arrays.asList(CHAR,VARCHAR,BINARY).contains(type)){
            return StringUtils.join("DEFAULT '", HiveIdentifierProcessor.INSTANCE.escapeString(column.getDefaultValue()),"'");
        }

        if(Arrays.asList(DATE).contains(type)){
            return StringUtils.join("DEFAULT '", HiveIdentifierProcessor.INSTANCE.escapeString(column.getDefaultValue()),"'");
        }

        if(Arrays.asList(TIMESTAMP).contains(type)){
            if("CURRENT_TIMESTAMP".equalsIgnoreCase(column.getDefaultValue().trim())){
                return StringUtils.join("DEFAULT ",column.getDefaultValue());
            }
            return StringUtils.join("DEFAULT '", HiveIdentifierProcessor.INSTANCE.escapeString(column.getDefaultValue()),"'");
        }

        return StringUtils.join("DEFAULT ", HiveSqlGuards.requireNumericDefault(column.getDefaultValue()));
    }

    private String buildNullable(TableColumn column,HiveColumnTypeEnum type) {
        if(!type.getColumnType().isSupportNullable()){
            return "";
        }
        if (column.getNullable()!=null && 1==column.getNullable()) {
            return "";
        } else {
            return "NOT NULL";
        }
    }

    private String buildDataType(TableColumn column, HiveColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();
        if (Arrays.asList(BINARY, VARCHAR, CHAR).contains(type)) {
            if (column.getColumnSize() == null || column.getColumnSize() == 0) {
                return columnType;
            }
            return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
        }


        if (Arrays.asList(TIMESTAMP).contains(type)) {
            if (column.getColumnSize() == null || column.getColumnSize() == 0) {
                return columnType;
            } else {
                return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
            }
        }


        if (DECIMAL == type) {
            if (column.getColumnSize() == null) {
                return columnType;
            }
            if (column.getDecimalDigits() == null) {
                return StringUtils.join(columnType, "(", column.getColumnSize() + ")");
            }
            return StringUtils.join(columnType, "(", column.getColumnSize() + "," + column.getDecimalDigits() + ")");
        }

        return columnType;
    }

    public String buildColumn(TableColumn column) {
        HiveColumnTypeEnum type = getByType(column.getColumnType());
        if (type == null) {
            return buildUnknownColumn(column);
        }
        StringBuilder script = new StringBuilder();

        script.append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName())).append(" ");
        script.append(buildDataType(column, type)).append(" ");
        return script.toString();
    }

    private static String buildUnknownColumn(TableColumn column) {
        StringBuilder script = new StringBuilder()
                .append(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(column.getName()))
                .append(" ")
                .append(HiveSqlGuards.requireColumnTypeExpression(column.getColumnType()));
        if (StringUtils.isNotBlank(column.getComment())) {
            script.append(" ").append(SQL_COMMENT)
                    .append(HiveIdentifierProcessor.INSTANCE.escapeString(column.getComment()))
                    .append("'");
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

    public static List<ColumnType> getTypes(){
       return Arrays.stream(HiveColumnTypeEnum.values()).map(columnTypeEnum ->
                columnTypeEnum.getColumnType()
        ).toList();
    }


}
