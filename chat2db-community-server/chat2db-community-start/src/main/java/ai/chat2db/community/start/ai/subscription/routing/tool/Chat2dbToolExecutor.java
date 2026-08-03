package ai.chat2db.community.start.ai.subscription.routing.tool;

/**
 * Adapter over existing Chat2DB tool executors. Routing never invents SQL/tool semantics.
 */
@FunctionalInterface
public interface Chat2dbToolExecutor {

    /**
     * Executes an allowlisted tool and returns its transient response to the active app-server turn.
     * The journal persists only a digest reference, never this response body.
     */
    String execute(String toolName, String argumentsJson) throws Exception;
}
