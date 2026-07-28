package ai.chat2db.plugin.mariadb.value;

import ai.chat2db.plugin.mariadb.value.factory.MariaDBValueProcessorFactory;
import ai.chat2db.plugin.mysql.value.MysqlValueProcessor;
import ai.chat2db.community.tools.util.EasyStringUtils;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;


public class MariaDBValueProcessor extends MysqlValueProcessor {


    private static final Logger log = LoggerFactory.getLogger(MariaDBValueProcessor.class);

    @Override
    public String getJdbcValue(JDBCDataValue dataValue) {
        Object value = dataValue.getObject();
        if (Objects.isNull(value)) {
            String stringValue = dataValue.getStringValue();
            if (Objects.nonNull(stringValue)) {
                return stringValue;
            }
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
        Object value = dataValue.getObject();
        if (Objects.isNull(value)) {
            String stringValue = dataValue.getStringValue();
            if (Objects.nonNull(stringValue)) {
                return EasyStringUtils.escapeAndQuoteString(stringValue);
            }
            return "NULL";
        }
        if (value instanceof String stringValue) {
            if (StringUtils.isBlank(stringValue)) {
                return EasyStringUtils.quoteString(stringValue);
            }
        }
        return convertJDBCValueStrByType(dataValue);
    }

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        if (FUNCTION_SET.contains(dataValue.getValue().toLowerCase())) {
            return dataValue.getValue();
        }
        try {
            DefaultValueProcessor valueProcessor = MariaDBValueProcessorFactory.getValueProcessor(dataValue.getDateTypeName());
            if (Objects.isNull(valueProcessor)) {
                return super.convertSQLValueByType(dataValue);
            }
            return valueProcessor.convertSQLValueByType(dataValue);
        } catch (Exception e) {
            log.warn("convertSQLValueByType error", e);
            return super.convertSQLValueByType(dataValue);
        }
    }

    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        String type = dataValue.getType();
        try {
            DefaultValueProcessor valueProcessor = MariaDBValueProcessorFactory.getValueProcessor(type);
            if (Objects.isNull(valueProcessor)) {
                return super.convertJDBCValueByType(dataValue);
            }
            return valueProcessor.convertJDBCValueByType(dataValue);
        } catch (Exception e) {
            log.warn("convertJDBCValueByType error", e);
            return super.convertJDBCValueByType(dataValue);
        }
    }

    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        String type = dataValue.getType();
        try {
            DefaultValueProcessor valueProcessor = MariaDBValueProcessorFactory.getValueProcessor(type);
            if (Objects.isNull(valueProcessor)) {
                return super.convertJDBCValueStrByType(dataValue);
            }
            return valueProcessor.convertJDBCValueStrByType(dataValue);
        } catch (Exception e) {
            log.warn("convertJDBCValueStrByType error", e);
            return super.convertJDBCValueStrByType(dataValue);
        }
    }
}
