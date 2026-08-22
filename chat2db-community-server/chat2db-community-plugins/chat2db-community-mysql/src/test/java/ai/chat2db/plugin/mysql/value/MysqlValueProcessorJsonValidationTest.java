package ai.chat2db.plugin.mysql.value;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

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
}
