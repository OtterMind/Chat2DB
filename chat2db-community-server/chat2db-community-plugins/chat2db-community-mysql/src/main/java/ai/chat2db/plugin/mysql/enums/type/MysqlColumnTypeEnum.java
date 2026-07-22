package ai.chat2db.plugin.mysql.enums.type;

import ai.chat2db.spi.IColumnBuilder;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.util.SqlUtils;
import com.google.common.collect.Maps;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_COMMENT_KEYWORD;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_COMMENT_SPACE_SINGLE_QUOTE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DROP_COLUMN_BACK_QUOTE;

import static ai.chat2db.plugin.mysql.constant.MysqlColumnTypeEnumConstants.*;
@Getter
public enum MysqlColumnTypeEnum implements IColumnBuilder {

    BIT("BIT", true, false, true, false, false, false, true, true, false, false, false),

    TINYINT("TINYINT", false, false, true, true, false, false, true, true, false, false, false),

    TINYINT_UNSIGNED("TINYINT UNSIGNED", false, false, true, true, false, false, true, true, false, false, false),

    SMALLINT("SMALLINT", false, false, true, true, false, false, true, true, false, false, false),

    SMALLINT_UNSIGNED("SMALLINT UNSIGNED", false, false, true, true, false, false, true, true, false, false, false),

    MEDIUMINT("MEDIUMINT", false, false, true, true, false, false, true, true, false, false, false),

    MEDIUMINT_UNSIGNED("MEDIUMINT UNSIGNED", false, false, true, true, false, false, true, true, false, false, false),

    INT("INT", false, false, true, true, false, false, true, true, false, false, false),


    INT_UNSIGNED("INT UNSIGNED", false, false, true, true, false, false, true, true, false, false, false),

    BIGINT("BIGINT", false, false, true, true, false, false, true, true, false, false, false),


    BIGINT_UNSIGNED("BIGINT UNSIGNED", false, false, true, true, false, false, true, true, false, false, false),


    DECIMAL("DECIMAL", true, true, true, false, false, false, true, true, false, false, false),

    DECIMAL_UNSIGNED("DECIMAL UNSIGNED", true, true, true, false, false, false, true, true, false, false, false),


    FLOAT("FLOAT", true, true, true, false, false, false, true, true, false, false, false),

    FLOAT_UNSIGNED("FLOAT UNSIGNED", true, true, true, false, false, false, true, true, false, false, false),

    DOUBLE("DOUBLE", true, true, true, false, false, false, true, true, false, false, false),

    DOUBLE_UNSIGNED("DOUBLE UNSIGNED", true, true, true, false, false, false, true, true, false, false, false),
    DATE("DATE", false, false, true, false, false, false, true, true, false, false, false),
    DATETIME("DATETIME", true, false, true, false, false, false, true, true, true, false, true),

    TIMESTAMP("TIMESTAMP", true, false, true, false, false, false, true, true, true, false, true),
    TIME("TIME", true, false, true, false, false, false, true, true, false, false, false),
    YEAR("YEAR", false, false, true, false, false, false, true, true, false, false, false),
    CHAR("CHAR", true, false, true, false, true, true, true, true, false, false, false),

    VARCHAR("VARCHAR", true, false, true, false, true, true, true, true, false, false, false),

    BINARY("BINARY", true, false, true, false, false, false, true, true, false, false, false),

    VARBINARY("VARBINARY", true, false, true, false, false, false, true, true, false, false, false),

    TINYBLOB("TINYBLOB", false, false, true, false, false, false, true, false, false, false, false),

    BLOB("BLOB", false, false, true, false, false, false, true, false, false, false, false),

    MEDIUMBLOB("MEDIUMBLOB", false, false, true, false, false, false, true, false, false, false, false),

    LONGBLOB("LONGBLOB", false, false, true, false, false, false, true, false, false, false, false),

    TINYTEXT("TINYTEXT", false, false, true, false, true, true, true, false, false, false, false),

    TEXT("TEXT", false, false, true, false, true, true, true, false, false, false, false),

    MEDIUMTEXT("MEDIUMTEXT", false, false, true, false, true, true, true, false, false, false, false),

    LONGTEXT("LONGTEXT", false, false, true, false, true, true, true, false, false, false, false),


    ENUM("ENUM", false, false, true, false, true, true, true, true, true, true, false),


    BOOL("BOOL", false, false, true, true, false, false, true, true, false, false, false),

    INTEGER("INTEGER", false, false, true, true, false, false, true, true, false, false, false),

    INTEGER_UNSIGNED("INTEGER UNSIGNED", false, false, true, true, false, false, true, true, false, false, false),

    REAL("REAL", true, true, true, false, false, false, true, true, false, false, false),

    SET("SET", false, false, true, false, true, true, true, true, true, true, false),


    GEOMETRY("GEOMETRY", false, false, true, false, false, false, true, false, false, false, false),

    POINT("POINT", false, false, true, false, false, false, true, false, false, false, false),

    LINESTRING("LINESTRING", false, false, true, false, false, false, true, false, false, false, false),

    POLYGON("POLYGON", false, false, true, false, false, false, true, false, false, false, false),

    MULTIPOINT("MULTIPOINT", false, false, true, false, false, false, true, false, false, false, false),

    MULTILINESTRING("MULTILINESTRING", false, false, true, false, false, false, true, false, false, false, false),

    MULTIPOLYGON("MULTIPOLYGON", false, false, true, false, false, false, true, false, false, false, false),

    GEOMETRYCOLLECTION("GEOMETRYCOLLECTION", false, false, true, false, false, false, true, false, false, false, false),

    JSON("JSON", false, false, true, false, false, false, true, false, false, false, false);


    private ColumnType columnType;

    public static MysqlColumnTypeEnum getByType(String dataType) {
        return COLUMN_TYPE_MAP.get(SqlUtils.removeDigits(dataType.toUpperCase()));
    }


    MysqlColumnTypeEnum(String dataTypeName, boolean supportLength, boolean supportScale, boolean supportNullable, boolean supportAutoIncrement, boolean supportCharset, boolean supportCollation, boolean supportComments, boolean supportDefaultValue, boolean supportExtent, boolean supportValue, boolean supportOnUpdateCurrentTimestamp) {
        this.columnType = new ColumnType(dataTypeName, supportLength, supportScale, supportNullable, supportAutoIncrement, supportCharset, supportCollation, supportComments, supportDefaultValue, supportExtent, supportValue, false, supportOnUpdateCurrentTimestamp);
    }

    private static Map<String, MysqlColumnTypeEnum> COLUMN_TYPE_MAP = Maps.newHashMap();

    static {
        for (MysqlColumnTypeEnum value : MysqlColumnTypeEnum.values()) {
            COLUMN_TYPE_MAP.put(value.getColumnType().getTypeName(), value);
        }
    }


    @Override
    public String buildCreateColumnSql(TableColumn column) {
        MysqlColumnTypeEnum type = COLUMN_TYPE_MAP.get(column.getColumnType().toUpperCase());
        if (type == null) {
            return buildDefaultColumn(column,true);
        }
        StringBuilder script = new StringBuilder();

        script.append("`").append(column.getName()).append("`").append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildCharset(column, type)).append(" ");

        script.append(buildCollation(column, type)).append(" ");

        script.append(buildNullable(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        script.append(OnUpdateCurrentTimestamp(column,type)).append(" ");

        script.append(buildExt(column, type)).append(" ");

        script.append(buildAutoIncrement(column, type)).append(" ");

        script.append(buildComment(column, type)).append(" ");

        return script.toString();
    }

    @Override
    public String buildAICreateColumnSql(TableColumn column) {
        MysqlColumnTypeEnum type = COLUMN_TYPE_MAP.get(column.getColumnType().toUpperCase());
        if (type == null) {
            return buildDefaultColumn(column,true);
        }
        StringBuilder script = new StringBuilder();

        script.append("`").append(column.getName()).append("`").append(" ");

        script.append(buildDataType(column, type)).append(" ");

        script.append(buildCharset(column, type)).append(" ");

        script.append(buildCollation(column, type)).append(" ");

        script.append(buildNullable(column, type)).append(" ");

        script.append(buildDefaultValue(column, type)).append(" ");

        script.append(OnUpdateCurrentTimestamp(column,type)).append(" ");

        script.append(buildExt(column, type)).append(" ");

        script.append(buildAutoIncrement(column, type)).append(" ");

        script.append(buildAICreateColumnCommentSql(column)).append(" ");

        return script.toString();
    }


    private String buildCharset(TableColumn column, MysqlColumnTypeEnum type) {
        if (!type.getColumnType().isSupportCharset() || StringUtils.isEmpty(column.getCharSetName())) {
            return "";
        }
        return StringUtils.join("CHARACTER SET ", column.getCharSetName());
    }

    private String buildCollation(TableColumn column, MysqlColumnTypeEnum type) {
        if (!type.getColumnType().isSupportCollation() || StringUtils.isEmpty(column.getCollationName())) {
            return "";
        }
        return StringUtils.join("COLLATE ", column.getCollationName());
    }

    @Override
    public String buildModifyColumn(TableColumn tableColumn) {

        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            return StringUtils.join(SQL_DROP_COLUMN_BACK_QUOTE, tableColumn.getName() + "`");
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            return StringUtils.join("ADD COLUMN ", buildCreateColumnSql(tableColumn));
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            if (!StringUtils.equalsIgnoreCase(tableColumn.getOldName(), tableColumn.getName())) {
                return StringUtils.join("CHANGE COLUMN `", tableColumn.getOldName(), "` ", buildCreateColumnSql(tableColumn));
            } else {
                return StringUtils.join("MODIFY COLUMN ", buildCreateColumnSql(tableColumn));
            }
        }
        return "";
    }

    public String buildModifyColumn(TableColumn tableColumn, boolean isMove, String columnName) {
        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            return StringUtils.join(SQL_DROP_COLUMN_BACK_QUOTE, tableColumn.getName() + "`");
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            if (isMove) {
                if (columnName.equals("-1")) {
                    return StringUtils.join("ADD COLUMN ", buildCreateColumnSql(tableColumn), " FIRST");
                } else {
                    return StringUtils.join("ADD COLUMN ", buildCreateColumnSql(tableColumn), " AFTER ", "`"+columnName+"`");
                }
            }
            return StringUtils.join("ADD COLUMN ", buildCreateColumnSql(tableColumn));
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            String sql;
            if (!StringUtils.equalsIgnoreCase(tableColumn.getOldName(), tableColumn.getName())) {
                sql = StringUtils.join("CHANGE COLUMN `", tableColumn.getOldName(), "` ", buildCreateColumnSql(tableColumn));
            } else {
                sql = StringUtils.join("MODIFY COLUMN ", buildCreateColumnSql(tableColumn));
            }
            return appendColumnPosition(sql, isMove, columnName);
        }
        if (isMove) {
            return appendColumnPosition(StringUtils.join("MODIFY COLUMN ", buildCreateColumnSql(tableColumn)), true,
                    columnName);
        }
        return "";
    }

    private String appendColumnPosition(String sql, boolean isMove, String columnName) {
        if (!isMove) {
            return sql;
        }
        if (columnName.equals("-1")) {
            return StringUtils.join(sql, " FIRST");
        }
        return StringUtils.join(sql, " AFTER ", "`" + columnName + "`");
    }

    private String buildAutoIncrement(TableColumn column, MysqlColumnTypeEnum type) {
        if (!type.getColumnType().isSupportAutoIncrement()) {
            return "";
        }
        if (column.getAutoIncrement() != null && column.getAutoIncrement()) {
            return "AUTO_INCREMENT";
        }
        return "";
    }

    private String buildComment(TableColumn column, MysqlColumnTypeEnum type) {
        if (!type.columnType.isSupportComments() || StringUtils.isEmpty(column.getComment())) {
            return "";
        }
        return StringUtils.join(SQL_COMMENT_SPACE_SINGLE_QUOTE, column.getComment(), "'");
    }

    private String buildExt(TableColumn column, MysqlColumnTypeEnum type) {
        if (!type.columnType.isSupportExtent() || StringUtils.isEmpty(column.getExtent())) {
            return "";
        }
        return column.getExtent();
    }

    private String buildDefaultValue(TableColumn column, MysqlColumnTypeEnum type) {
        if (!type.getColumnType().isSupportDefaultValue() || StringUtils.isEmpty(column.getDefaultValue())) {
            return "";
        }

        if ("EMPTY_STRING".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT ''");
        }

        if ("NULL".equalsIgnoreCase(column.getDefaultValue().trim())) {
            return StringUtils.join("DEFAULT NULL");
        }

        if (Arrays.asList(CHAR, VARCHAR, BINARY, VARBINARY, SET, ENUM).contains(type)) {
            return StringUtils.join("DEFAULT '", column.getDefaultValue(), "'");
        }

        if (Arrays.asList(DATE, TIME, YEAR).contains(type)) {
            return StringUtils.join("DEFAULT '", column.getDefaultValue(), "'");
        }

        if (Arrays.asList(DATETIME, TIMESTAMP).contains(type)) {
            if ("CURRENT_TIMESTAMP".equalsIgnoreCase(column.getDefaultValue().trim())) {
                return StringUtils.join("DEFAULT ", column.getDefaultValue());

            }
            return StringUtils.join("DEFAULT '", column.getDefaultValue(), "'");
        }

        return StringUtils.join("DEFAULT ", column.getDefaultValue());
    }

    private String OnUpdateCurrentTimestamp(TableColumn column, MysqlColumnTypeEnum type) {
        if (column.getOnUpdateCurrentTimestamp() != null && column.getOnUpdateCurrentTimestamp()) {
            return  "ON UPDATE CURRENT_TIMESTAMP";
        }
        return "";
    }


    private String buildNullable(TableColumn column, MysqlColumnTypeEnum type) {
        if (!type.getColumnType().isSupportNullable()) {
            return "";
        }
        if (column.getNullable() != null && 1 == column.getNullable()) {
            return "NULL";
        } else {
            return "NOT NULL";
        }
    }

    private String buildDataType(TableColumn column, MysqlColumnTypeEnum type) {
        String columnType = type.columnType.getTypeName();
        if (Arrays.asList(BINARY, VARBINARY, VARCHAR, CHAR).contains(type)) {
            return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
        }

        if (Arrays.asList(BIT).contains(type)) {
            return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
        }

        if (Arrays.asList(TIME, DATETIME, TIMESTAMP).contains(type)) {
            if (column.getColumnSize() == null || column.getColumnSize() == 0) {
                return columnType;
            } else {
                return StringUtils.join(columnType, "(", column.getColumnSize(), ")");
            }
        }


        if (Arrays.asList(DECIMAL, FLOAT, DOUBLE).contains(type)) {
            if (Arrays.asList(DECIMAL).contains(type) && column.getColumnSize() == null && column.getDecimalDigits() != null) {
                return StringUtils.join(columnType, "(", DEFAULT_DECIMAL_COLUMN_SIZE + "," + column.getDecimalDigits() + ")");
            }
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

        if (Arrays.asList(DECIMAL_UNSIGNED, FLOAT_UNSIGNED, DOUBLE_UNSIGNED).contains(type)) {
            if (Arrays.asList(DECIMAL_UNSIGNED).contains(type) && column.getColumnSize() == null && column.getDecimalDigits() != null) {
                return unsignedDataType(columnType, "(" + DEFAULT_DECIMAL_COLUMN_SIZE + "," + column.getDecimalDigits() + ")");
            }
            if (column.getColumnSize() == null || column.getDecimalDigits() == null) {
                return columnType;
            }
            if (column.getColumnSize() != null && column.getDecimalDigits() == null) {
                return unsignedDataType(columnType, "(" + column.getColumnSize() + ")");
            }
            if (column.getColumnSize() != null && column.getDecimalDigits() != null) {
                return unsignedDataType(columnType, "(" + column.getColumnSize() + "," + column.getDecimalDigits() + ")");
            }
        }

        if (Arrays.asList(SET, ENUM).contains(type)) {
            if (!StringUtils.isEmpty(column.getValue())) {
                return StringUtils.join(columnType, "(", column.getValue(), ")");
            }
        }

        return columnType;
    }

    public String buildColumn(TableColumn column) {
        MysqlColumnTypeEnum type = COLUMN_TYPE_MAP.get(column.getColumnType().toUpperCase());
        if (type == null) {
            return "";
        }
        StringBuilder script = new StringBuilder();

        script.append("`").append(column.getName()).append("`").append(" ");
        script.append(buildDataType(column, type)).append(" ");
        if (StringUtils.isNoneBlank(column.getComment())) {
            script.append(SQL_COMMENT_KEYWORD).append(" ").append("'").append(column.getComment()).append("'").append(" ");
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

    public static List<ColumnType> getTypes() {
        return Arrays.stream(MysqlColumnTypeEnum.values()).map(columnTypeEnum ->
                columnTypeEnum.getColumnType()
        ).toList();
    }


}
