package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.metadata.Event;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Provides database-specific event lifecycle operations.
 */
public interface IEventManager {

    List<Map<String, Object>> list(Connection connection, String databaseName);

    Event detail(Connection connection, String databaseName, String schemaName, String eventName);

    Map<String, Object> schedulerStatus(Connection connection, String databaseName);

    String buildDropEvent(String databaseName, String eventName);

    String buildAlterEventEnabled(String databaseName, String eventName, boolean enabled);
}
