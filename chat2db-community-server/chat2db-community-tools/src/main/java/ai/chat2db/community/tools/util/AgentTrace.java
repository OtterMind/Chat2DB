package ai.chat2db.community.tools.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

/** Lifecycle diagnostics contain identifiers and measurements, never prompts, tool arguments or credentials. */
public final class AgentTrace {
    private static final Logger LOG = LoggerFactory.getLogger("chat2db.agent.trace");
    private AgentTrace() { }

    public static void record(String stage, String sessionId, String runId, Map<String, ?> fields) {
        LOG.info("AgentTrace stage={} session={} run={} fields={}", stage, sessionId, runId, fields);
    }
}
