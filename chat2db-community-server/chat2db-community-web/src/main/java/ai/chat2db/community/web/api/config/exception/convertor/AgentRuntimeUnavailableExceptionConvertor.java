package ai.chat2db.community.web.api.config.exception.convertor;

import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import ai.chat2db.community.tools.wrapper.result.ActionResult;

public class AgentRuntimeUnavailableExceptionConvertor
        implements IExceptionConvertor<AgentRuntimeUnavailableException> {

    @Override
    public ActionResult convert(AgentRuntimeUnavailableException exception) {
        return ActionResult.fail("agent.runtimeUnavailable", exception.getMessage(), null);
    }
}
