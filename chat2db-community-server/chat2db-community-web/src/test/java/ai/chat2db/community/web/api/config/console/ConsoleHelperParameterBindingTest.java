package ai.chat2db.community.web.api.config.console;

import ai.chat2db.community.web.api.model.request.agent.AgentTaskScheduleCronPreviewRequest;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleHelperParameterBindingTest {

    @Test
    void desktopRequestPreservesAllFieldsInAComplexGetParameter() {
        Object[] values = ConsoleHelper.getValues(
                "{\"expression\":\"0 9 * * *\",\"timezone\":\"Asia/Shanghai\"}",
                new Class[]{AgentTaskScheduleCronPreviewRequest.class},
                new ConsoleResult());

        AgentTaskScheduleCronPreviewRequest request =
                (AgentTaskScheduleCronPreviewRequest) values[0];
        assertEquals("0 9 * * *", request.getExpression());
        assertEquals("Asia/Shanghai", request.getTimezone());
    }
}
