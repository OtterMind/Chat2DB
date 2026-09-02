package ai.chat2db.plugin.mysql.value;

import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.plugin.mysql.MysqlMetaData;
import ai.chat2db.spi.IValueProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlValueProcessorJsonValidationTest {

    @Test
    void acceptsOneJsonValueIncludingScalarsAndNull() {
        assertDoesNotThrow(() -> MysqlValueProcessor.validateJsonValue("{\"name\":\"chat2db\"}"));
        assertDoesNotThrow(() -> MysqlValueProcessor.validateJsonValue("[1, true, null]"));
        assertDoesNotThrow(() -> MysqlValueProcessor.validateJsonValue("42"));
        assertDoesNotThrow(() -> MysqlValueProcessor.validateJsonValue("null"));
    }

    @Test
    void rejectsEmptyMalformedAndTrailingInput() {
        assertThrows(BusinessException.class, () -> MysqlValueProcessor.validateJsonValue(""));
        assertThrows(BusinessException.class, () -> MysqlValueProcessor.validateJsonValue("{'name':'chat2db'}"));
        assertThrows(BusinessException.class, () -> MysqlValueProcessor.validateJsonValue("{\"name\":\"chat2db\"} trailing"));
        assertThrows(BusinessException.class, () -> MysqlValueProcessor.validateJsonValue("true false"));
    }

    @Test
    void mysqlValueProcessorPathAcceptsJsonScalarsAndDistinguishesSqlNull() {
        IValueProcessor valueProcessor = new MysqlMetaData().getValueProcessor();

        assertEquals("'42'", valueProcessor.getSqlValueString(jsonValue("42")));
        assertEquals("'true'", valueProcessor.getSqlValueString(jsonValue("true")));
        assertEquals("'null'", valueProcessor.getSqlValueString(jsonValue("null")));
        assertEquals("NULL", valueProcessor.getSqlValueString(jsonValue(null)));
    }

    @Test
    void mysqlValueProcessorPathRejectsEmptyMalformedAndTrailingJsonInput() {
        IValueProcessor valueProcessor = new MysqlMetaData().getValueProcessor();

        assertThrows(BusinessException.class, () -> valueProcessor.getSqlValueString(jsonValue("")));
        assertThrows(BusinessException.class, () -> valueProcessor.getSqlValueString(jsonValue("   ")));
        assertThrows(BusinessException.class, () -> valueProcessor.getSqlValueString(jsonValue("{'name':'chat2db'}")));
        assertThrows(BusinessException.class,
                () -> valueProcessor.getSqlValueString(jsonValue("{\"name\":\"chat2db\"} trailing")));
        assertThrows(BusinessException.class, () -> valueProcessor.getSqlValueString(jsonValue("true false")));
    }

    private static SQLDataValue jsonValue(String value) {
        DataType dataType = new DataType();
        dataType.setDataTypeName("JSON");

        SQLDataValue dataValue = new SQLDataValue();
        dataValue.setDataType(dataType);
        dataValue.setValue(value);
        return dataValue;
    }
}
