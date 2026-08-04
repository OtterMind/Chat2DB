package ai.chat2db.community.domain.api.model.operation;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationSerializationTest {

    @Test
    void nameCustomizedIsPreservedByBothSerializers() throws Exception {
        Operation operation = new Operation();
        operation.setId(1L);
        operation.setName("monthly report");
        operation.setNameCustomized(true);

        Operation jacksonCopy = new ObjectMapper().readValue(
            new ObjectMapper().writeValueAsString(operation),
            Operation.class
        );
        Operation fastjsonCopy = JSON.parseObject(JSON.toJSONString(operation), Operation.class);

        assertTrue(jacksonCopy.getNameCustomized());
        assertTrue(fastjsonCopy.getNameCustomized());
    }

    @Test
    void legacyRecordsWithoutNameCustomizedRemainDetectable() throws Exception {
        String legacyJson = "{\"id\":1,\"name\":\"orders[local]\"}";

        assertNull(new ObjectMapper().readValue(legacyJson, Operation.class).getNameCustomized());
        assertNull(JSON.parseObject(legacyJson, Operation.class).getNameCustomized());
    }
}
