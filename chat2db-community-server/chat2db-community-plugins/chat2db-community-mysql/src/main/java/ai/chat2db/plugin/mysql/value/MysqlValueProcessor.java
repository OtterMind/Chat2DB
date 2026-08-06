package ai.chat2db.plugin.mysql.value;

import ai.chat2db.plugin.mysql.enums.type.MysqlColumnTypeEnum;
import ai.chat2db.plugin.mysql.value.factory.MysqlValueProcessorFactory;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.EasyStringUtils;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;


public class MysqlValueProcessor extends DefaultValueProcessor {
    public static final Set<String> FUNCTION_SET = Set.of("now()", "default");
    private static final Logger log = LoggerFactory.getLogger(MysqlValueProcessor.class);


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
    public String getSqlValueString(SQLDataValue dataValue) {
        String value = dataValue.getValue();
        if (value != null && !FUNCTION_SET.contains(value.toLowerCase())) {
            String dataTypeName = dataValue.getDataType() != null
                    ? dataValue.getDataType().getDataTypeName() : null;
            if ("JSON".equalsIgnoreCase(dataTypeName)) {
                try {
                    JSON.parse(value);
                } catch (JSONException e) {
                    throw new BusinessException("mysql.json.invalid",
                            new Object[]{e.getMessage()}, e);
                }
            }
        }
        return super.getSqlValueString(dataValue);
    }

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        if (FUNCTION_SET.contains(dataValue.getValue().toLowerCase())) {
            return dataValue.getValue();
        }
        try {
            DefaultValueProcessor valueProcessor = MysqlValueProcessorFactory.getValueProcessor(dataValue.getDateTypeName());
            if (Objects.nonNull(valueProcessor)) {
                return valueProcessor.convertSQLValueByType(dataValue);
            }
        } catch (Exception e) {
            log.warn("convertSQLValueByType error", e);
            return super.convertSQLValueByType(dataValue);
        }
        return super.convertSQLValueByType(dataValue);
    }

    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        String type = dataValue.getType();
        try {
            DefaultValueProcessor valueProcessor = MysqlValueProcessorFactory.getValueProcessor(type);
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
        DefaultValueProcessor valueProcessor;
        try {
            valueProcessor = MysqlValueProcessorFactory.getValueProcessor(type);
            if (Objects.nonNull(valueProcessor)) {
                return valueProcessor.convertJDBCValueStrByType(dataValue);
            }
        } catch (Exception e) {
            log.warn("convertJDBCValueStrByType error", e);
            return super.convertJDBCValueStrByType(dataValue);
        }
        return super.convertJDBCValueStrByType(dataValue);
    }


    @Override
    public boolean isStringDataType(String dataType) {
        return StringUtils.equalsAnyIgnoreCase(dataType, MysqlColumnTypeEnum.CHAR.name(),
                                               MysqlColumnTypeEnum.VARCHAR.name(),
                                               MysqlColumnTypeEnum.SET.name(),
                                               MysqlColumnTypeEnum.ENUM.name(),
                                               MysqlColumnTypeEnum.TEXT.name());
    }
}
