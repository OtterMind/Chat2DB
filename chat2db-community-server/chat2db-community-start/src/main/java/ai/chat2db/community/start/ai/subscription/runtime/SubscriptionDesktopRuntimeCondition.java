package ai.chat2db.community.start.ai.subscription.runtime;

import ai.chat2db.community.tools.util.ConfigUtils;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Registers subscription beans only on the explicitly enabled packaged Community desktop surface. */
public final class SubscriptionDesktopRuntimeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean featureEnabled = context.getEnvironment().getProperty(
                SubscriptionAiRuntime.FEATURE_PROPERTY, Boolean.class, false);
        return matchesValues(featureEnabled, ConfigUtils.isCommunity(), ConfigUtils.isDesktop(),
                ConfigUtils.isShowGUI(), ConfigUtils.isRelease());
    }

    static boolean matchesValues(boolean featureEnabled, boolean community, boolean desktop,
                                 boolean gui, boolean release) {
        return featureEnabled && community && desktop && gui && release;
    }
}
