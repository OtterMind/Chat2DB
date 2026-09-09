package ai.chat2db.community.jcef.agent;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DesktopAgentRuntimeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return "DESKTOP".equalsIgnoreCase(context.getEnvironment().getProperty("chat2db.mode"));
    }
}
