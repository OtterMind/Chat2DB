package ai.chat2db.community.jcef.agent;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentRuntimeConditionTest {

    private final LocalAgentRuntimeCondition condition = new LocalAgentRuntimeCondition();

    @Test
    void enablesCommunityWebAndDesktopButNotCliOrRemoteWeb() {
        assertTrue(condition.matches(context(Map.of("chat2db.runtime.mode", "community")), null));
        assertTrue(condition.matches(context(Map.of("chat2db.mode", "DESKTOP")), null));
        assertFalse(condition.matches(context(Map.of("chat2db.runtime.mode", "cli")), null));
        assertFalse(condition.matches(context(Map.of("chat2db.runtime.mode", "enterprise")), null));
    }

    private ConditionContext context(Map<String, Object> properties) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return new ConditionContext() {
            @Override public org.springframework.beans.factory.support.BeanDefinitionRegistry getRegistry() {
                return null;
            }
            @Override public org.springframework.beans.factory.config.ConfigurableListableBeanFactory getBeanFactory() {
                return null;
            }
            @Override public ConfigurableEnvironment getEnvironment() { return environment; }
            @Override public org.springframework.core.io.ResourceLoader getResourceLoader() { return null; }
            @Override public ClassLoader getClassLoader() { return null; }
        };
    }
}
