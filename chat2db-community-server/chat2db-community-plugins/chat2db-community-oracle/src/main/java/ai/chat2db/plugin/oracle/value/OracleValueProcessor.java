package ai.chat2db.plugin.oracle.value;

import ai.chat2db.plugin.oracle.enums.type.OracleColumnTypeEnum;
import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;
import ai.chat2db.plugin.oracle.value.factory.OracleValueProcessorFactory;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;


public class OracleValueProcessor extends DefaultValueProcessor {


    private static final Logger log = LoggerFactory.getLogger(OracleValueProcessor.class);
    @Override
    public String getJdbcValue(JDBCDataValue dataValue) {
        if (StringUtils.equalsAny(dataValue.getType(),
                                  OracleColumnTypeEnum.LONG_RAW.getColumnType().getTypeName(),
                                  OracleColumnTypeEnum.LONG.getColumnType().getTypeName())) {
            return convertJDBCValueByType(dataValue);
        }
        Object value = dataValue.getObject();
        if (Objects.isNull(value)) {
            return null;
        }
        if (value instanceof String emptyStr) {
            if (StringUtils.isBlank(emptyStr)) {
                return emptyStr;
            }
        }
        return convertJDBCValueByType(dataValue);
    }


    @Override
    public String getJdbcSqlValueString(JDBCDataValue dataValue) {
        if (OracleColumnTypeEnum.LONG_RAW.getColumnType().getTypeName().equalsIgnoreCase(dataValue.getType())) {
            return convertJDBCValueStrByType(dataValue);
        }
        Object value = dataValue.getObject();
        if (Objects.isNull(value)) {
            return "NULL";
        }
        if (value instanceof String stringValue) {
            if (StringUtils.isBlank(stringValue)) {
                return OracleIdentifierProcessor.INSTANCE.quoteStringLiteral(stringValue);
            }
        }
        return convertJDBCValueStrByType(dataValue);
    }

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        try {
            DefaultValueProcessor valueProcessor = OracleValueProcessorFactory.getValueProcessor(dataValue.getDateTypeName());
            if (Objects.nonNull(valueProcessor)) {
                return valueProcessor.convertSQLValueByType(dataValue);
            }
        } catch (Exception e) {
            log.warn("convertSQLValueByType error", e);
            return OracleIdentifierProcessor.INSTANCE.quoteStringLiteral(dataValue.getValue());
        }
        return OracleIdentifierProcessor.INSTANCE.quoteStringLiteral(dataValue.getValue());
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        String type = dataValue.getType();
        try {
            DefaultValueProcessor valueProcessor = OracleValueProcessorFactory.getValueProcessor(type);
            if (Objects.nonNull(valueProcessor)) {
                return valueProcessor.convertJDBCValueByType(dataValue);
            }
        } catch (Exception e) {
            log.warn("convertJDBCValueByType error", e);
            return super.convertJDBCValueByType(dataValue);
        }
        return super.convertJDBCValueByType(dataValue);
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        String type = dataValue.getType();
        try {
            DefaultValueProcessor valueProcessor = OracleValueProcessorFactory.getValueProcessor(type);
            if (Objects.nonNull(valueProcessor)) {
                return valueProcessor.convertJDBCValueStrByType(dataValue);
            }
        } catch (Exception e) {
            log.warn("convertJDBCValueStrByType error", e);
            return quoteJdbcString(dataValue);
        }
        return quoteJdbcString(dataValue);
    }

    private static String quoteJdbcString(JDBCDataValue dataValue) {
        String value = dataValue.getString();
        return value == null ? "NULL" : OracleIdentifierProcessor.INSTANCE.quoteStringLiteral(value);
    }

    @Override
    public boolean isStringDataType(String dataType) {
        return StringUtils.equalsAnyIgnoreCase(dataType, OracleColumnTypeEnum.CHAR.name(),
                                               OracleColumnTypeEnum.VARCHAR.name(),
                                               OracleColumnTypeEnum.VARCHAR2.name(),
                                               OracleColumnTypeEnum.NCHAR.name(),
                                               OracleColumnTypeEnum.NVARCHAR2.name());
    }
}
