package ai.chat2db.community.start.ai.subscription.appserver;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Receives redacted server notifications. Listeners must not log raw auth payloads.
 */
@FunctionalInterface
public interface AppServerEventListener {

    void onNotification(String method, JsonNode redactedParams);
}
