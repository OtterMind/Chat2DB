package ai.chat2db.community.domain.api.enums.plugin;

import ai.chat2db.community.tools.enums.IBaseEnum;
import ai.chat2db.community.tools.util.EasyStringUtils;
import lombok.Getter;


@Getter
public enum DataTypeEnum implements IBaseEnum<String> {



    BOOLEAN("Boolean value"),




    NUMERIC("number"),




    STRING("string"),




    DATETIME("date"),




    BINARY("binary"),




    CONTENT("content"),




    STRUCT("structure"),




    DOCUMENT("document"),




    ARRAY("array"),




    OBJECT("object"),




    REFERENCE("reference"),




    ROWID("rowid"),




    ANY("any"),




    UNKNOWN("unknow"),




    CHAT2DB_ROW_NUMBER("Row number"),




    BIT("bit"),
    ;

    final String description;

    DataTypeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }

    public static DataTypeEnum getByCode(String code) {
        for (DataTypeEnum value : DataTypeEnum.values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }
        return DataTypeEnum.UNKNOWN;
    }

    public String getSqlValue(String value) {
        if (this == DataTypeEnum.BOOLEAN) {
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                return value;
            } else {
                return getStringValue(value);
            }
        }
        if (this == DataTypeEnum.NUMERIC) {
            return value;
        }
        if (this == DataTypeEnum.STRING) {
            return getStringValue(value);
        }
        if (this == DataTypeEnum.DATETIME) {
            return getStringValue(value);
        }
        if (this == DataTypeEnum.BINARY) {
            return "''";
        }
        if (this == DataTypeEnum.CONTENT) {
            return getStringValue(value);
        }
        if (this == DataTypeEnum.STRUCT) {
            return getStringValue(value);
        }
        if (this == DataTypeEnum.DOCUMENT) {
            return getStringValue(value);
        }
        if (this == DataTypeEnum.ARRAY) {
            return getStringValue(value);
        }
        if (this == DataTypeEnum.OBJECT) {
            return getStringValue(value);
        }
        if (this == DataTypeEnum.REFERENCE) {
            return getStringValue(value);
        }
        if (this == DataTypeEnum.ROWID) {
            return getStringValue(value);
        }
        if (this == DataTypeEnum.ANY) {
            return getStringValue(value);
        }
        if (this == DataTypeEnum.UNKNOWN) {
            return getStringValue(value);
        }
        return getStringValue(value);
    }

    public static String getStringValue(String value) {
        return EasyStringUtils.escapeAndQuoteString(value);
    }

}
