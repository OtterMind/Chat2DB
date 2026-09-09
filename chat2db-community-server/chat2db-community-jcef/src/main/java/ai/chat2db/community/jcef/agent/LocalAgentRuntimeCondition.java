package ai.chat2db.community.jcef.agent;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class LocalAgentRuntimeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String clientMode = context.getEnvironment().getProperty("chat2db.mode");
        String runtimeMode = context.getEnvironment().getProperty("chat2db.runtime.mode");
        return "DESKTOP".equalsIgnoreCase(clientMode) || "community".equalsIgnoreCase(runtimeMode);
    }
}
