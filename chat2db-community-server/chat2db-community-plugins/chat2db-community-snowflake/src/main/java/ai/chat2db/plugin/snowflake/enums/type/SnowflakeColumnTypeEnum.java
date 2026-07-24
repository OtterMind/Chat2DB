package ai.chat2db.plugin.snowflake.enums.type;

import ai.chat2db.spi.IColumnBuilder;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static ai.chat2db.plugin.snowflake.constant.SnowflakeColumnTypeEnumConstants.*;
public enum SnowflakeColumnTypeEnum implements IColumnBuilder {

    NUMBER("NUMBER", true, true, true, true, false, false, true, true, false, false),
    DECIMAL("DECIMAL", true, true, true, true, false, false, true, true, false, false),
    NUMERIC("NUMERIC", true, true, true, true, false, false, true, true, false, false),
    INT("INT", false, false, true, true, false, false, true, true, false, false),
    INTEGER("INTEGER", false, false, true, true, false, false, true, true, false, false),
    BIGINT("BIGINT", false, false, true, true, false, false, true, true, false, false),
    SMALLINT("SMALLINT", false, false, true, true, false, false, true, true, false, false),
    TINYINT("TINYINT", false, false, true, true, false, false, true, true, false, false),
    BYTEINT("BYTEINT", false, false, true, true, false, false, true, true, false, false),
    FLOAT("FLOAT", true, true, true, true, false, false, true, true, false, false),
    DOUBLE("DOUBLE", true, true, true, true, false, false, true, true, false, false),

    VARCHAR("VARCHAR", true, false, true, false, true, true, true, true, false, false),
    CHAR("CHAR", true, false, true, false, true, true, true, true, false, false),
    STRING("STRING", true, false, true, false, true, true, true, true, false, false),
    TEXT("TEXT", true, false, true, false, true, true, true, true, false, false),
    BINARY("BINARY", true, false, true, false, true, true, true, true, false, false),
    VARBINARY("VARBINARY", true, false, true, false, true, true, true, true, false, false),

    BOOLEAN("BOOLEAN", false, false, true, false, false, false, true, true, false, false),

    DATE("DATE", false, false, true, false, false, false, true, true, false, false),
    DATETIME("DATETIME", true, false, true, false, false, false, true, true, true, false),
    TIME("TIME", true, false, true, false, false, false, true, true, false, false),
    TIMESTAMP("TIMESTAMP", true, false, true, false, false, false, true, true, true, false),
    TIMESTAMP_LTZ("TIMESTAMPLTZ", true, false, true, false, false, false, true, true, true, false),
    TIMESTAMP_NTZ("TIMESTAMPNTZ", true, false, true, false, false, false, true, true, true, false),
    TIMESTAMP_TZ("TIMESTAMPTZ", true, false, true, false, false, false, true, true, true, false),
    VARIANT("VARIANT", true, false, true, false, false, false, true, true, false, false),
    OBJECT("OBJECT", true, false, true, false, false, false, true, true, false, false),
    ARRAY("ARRAY", true, false, true, false, false, false, true, true, false, false),
    GEOGRAPHY("GEOGRAPHY", true, false, true, false, false, false, true, true, false, false),

    ;








    private ColumnType columnType;

    public static SnowflakeColumnTypeEnum getByType(String dataType) {
        return COLUMN_TYPE_MAP.get(dataType.toUpperCase());
    }

    public ColumnType getColumnType() {
        return columnType;
    }

    SnowflakeColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale, boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset, boolean supportCollation, boolean supportComments, boolean supportDefaultValue, boolean supportExtent, boolean supportValue) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable, supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue, supportExtent,supportValue,false);
    }

    private static Map<String, SnowflakeColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (SnowflakeColumnTypeEnum value : SnowflakeColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName(), value);
        }
    }
    @Override
    public String buildCreateColumnSql(TableColumn column) {

        SnowflakeColumnTypeEnum type = COLUMN_TYPE_MAP.get(column.getColumnType().toUpperCase());
        if (type == null) {
            return buildDefaultColumn(column, true);
        }
        StringBuilder script = new StringBuilder();

        script.append("\"").append(column.getName()).append("\"").append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildCollation(column,type)).append(" ");

        script.append(buildNullable(column,type)).append(" ");

        script.append(buildDefaultValue(column,type)).append(" ");

        script.append(buildAutoIncrement(column,type)).append(" ");

        script.append(buildComment(column,type)).append(" ");

        return script.toString();
    }

    @Override
    public String buildModifyColumn(TableColumn tableColumn) {
        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            return StringUtils.join(SQL_DROP_COLUMN, tableColumn.getName());
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            return StringUtils.join("ADD COLUMN ", buildCreateColumnSql(tableColumn));
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            if (!StringUtils.equalsIgnoreCase(tableColumn.getOldName(), tableColumn.getName())) {
                return StringUtils.join("CHANGE COLUMN ", tableColumn.getOldName(), " ", buildCreateColumnSql(tableColumn));
            } else {
                return StringUtils.join("MODIFY COLUMN ", buildCreateColumnSql(tableColumn));
            }
        }
        return "";
    }

    private String buildDataType(TableColumn column, SnowflakeColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();
        if (EditStatusEnum.MODIFY.name().equals(column.getEditStatus())
                && StringUtils.equalsIgnoreCase(column.getOldColumn().getTableName(), column.getTableName())
                && Objects.equals(column.getOldColumn().getColumnSize(), column.getColumnSize())
                && Objects.equals(column.getOldColumn().getDecimalDigits(), column.getDecimalDigits())) {
            return "";
        }
        if (Arrays.asList(BINARY, VARBINARY, VARCHAR, CHAR, STRING, TEXT).contains(type)) {
            if (column.getColumnSize() == null || column.getColumnSize() == 0){
                return columnType;
            } else {
                return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
            }
        }

        if (Arrays.asList(DATE, TIME, DATETIME, TIMESTAMP, TIMESTAMP_TZ, TIMESTAMP_LTZ, TIMESTAMP_LTZ).contains(type)) {
            if (column.getColumnSize() == null || column.getColumnSize() == 0) {
                return columnType;
            } else {
                return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
            }
        }

        if (Arrays.asList(DECIMAL, FLOAT, DOUBLE,TINYINT, INT, NUMBER, NUMERIC, INTEGER, BIGINT, SMALLINT, BYTEINT).contains(type)) {
            if (column.getColumnSize() == null || column.getDecimalDigits() == null) {
                return columnType;
            }
            if (column.getColumnSize() != null && column.getDecimalDigits() == null) {
                return StringUtils.join(columnType, "(", column.getColumnSize() + ")");
            }
            if (column.getColumnSize() != null && column.getDecimalDigits() != null) {
                return StringUtils.join(columnType, "(", column.getColumnSize() + "," + column.getDecimalDigits() + ")");
            }
        }

        return columnType;
    }

    private String buildCollation(TableColumn column, SnowflakeColumnTypeEnum type) {
        if(!type.getColumnType().isSupportCollation() || StringUtils.isEmpty(column.getCollationName())){
            return "";
        }
        return StringUtils.join("COLLATE ", "'", column.getCollationName(), "'");
    }

    private String buildNullable(TableColumn column, SnowflakeColumnTypeEnum type) {
        if(!type.getColumnType().isSupportNullable()){
            return "";
        }
        if (EditStatusEnum.MODIFY.name().equals(column.getEditStatus())
                && column.getNullable() != null
                && column.getOldColumn().getNullable() != null
                && !Objects.equals(column.getNullable(), column.getOldColumn().getNullable())) {
            if (column.getNullable()!=null && 1==column.getNullable()) {
                return "DROP NOT NULL";
            } else {
                return "NOT NULL";
            }
        } else if (EditStatusEnum.ADD.name().equals(column.getEditStatus())) {
            if (column.getNullable()!=null && 1==column.getNullable()) {
                return "";
            } else {
                return "NOT NULL";
            }
        }
        return "";
    }

    private String buildDefaultValue(TableColumn column, SnowflakeColumnTypeEnum type) {
        if(!type.getColumnType().isSupportDefaultValue() || StringUtils.isEmpty(column.getDefaultValue())){
            return "";
        }

        if("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())){
            return StringUtils.join(SQL_SET_DEFAULT_3);
        }

        if("NULL".equalsIgnoreCase(column.getDefaultValue().trim())){
            return StringUtils.join(SQL_SET_DEFAULT_NULL);
        }

        if(Arrays.asList(CHAR,VARCHAR,BINARY,VARBINARY).contains(type)){
            return StringUtils.join(SQL_SET_DEFAULT_2,column.getDefaultValue(),"'");
        }

        if(Arrays.asList(DATE,TIME).contains(type)){
            return StringUtils.join(SQL_SET_DEFAULT_2,column.getDefaultValue(),"'");
        }

        if(Arrays.asList(DATETIME,TIMESTAMP).contains(type)){
            if("CURRENT_TIMESTAMP".equalsIgnoreCase(column.getDefaultValue().trim())){
                return StringUtils.join(SQL_SET_DEFAULT,column.getDefaultValue());
            }
            return StringUtils.join(SQL_SET_DEFAULT_2,column.getDefaultValue(),"'");
        }

        return StringUtils.join(SQL_SET_DEFAULT,column.getDefaultValue());
    }

    private String buildAutoIncrement(TableColumn column, SnowflakeColumnTypeEnum type) {
        if(!type.getColumnType().isSupportAutoIncrement()){
            return "";
        }
        if (column.getAutoIncrement() != null && column.getAutoIncrement()) {
            return "identity";
        }
        return "";
    }

    private String buildComment(TableColumn column, SnowflakeColumnTypeEnum type) {
        if(!type.columnType.isSupportComments() || StringUtils.isEmpty(column.getComment())){
            return "";
        }
        return StringUtils.join(SQL_COMMENT,column.getComment(),"'");
    }

    public static List<ColumnType> getTypes(){
        return Arrays.stream(SnowflakeColumnTypeEnum.values()).map(columnTypeEnum ->
                columnTypeEnum.getColumnType()
        ).toList();
    }


}
