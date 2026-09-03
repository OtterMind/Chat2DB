package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.metadata.Event;

import java.util.List;
import java.util.Map;

/**
 * MySQL Event lifecycle management (MYSQL-OBJ-013): view, create via editor, modify,
 * enable/disable, and delete. The global event_scheduler state is reported so a created
 * event that will not run is surfaced as "created but not scheduled".
 */
public interface IDbEventService {

    /**
     * Lists events in a database with their schedule and status.
     *
     * @param databaseName the database name.
     * @return event maps (EVENT_NAME, STATUS, EVENT_TYPE, schedule fields, etc.).
     */
    List<Map<String, Object>> list(String databaseName);

    /**
     * Returns metadata and editable DDL for a single event.
     *
     * @param databaseName the database name.
     * @param schemaName   optional schema name for dialects that use schemas.
     * @param eventName    the event name.
     * @return event metadata, or null when no matching event exists.
     */
    Event detail(String databaseName, String schemaName, String eventName);

    /**
     * Returns the scheduler state and whether events are missing.
     *
     * @param databaseName the database name whose event count should be reported.
     * @return map with {@code schedulerEnabled} and {@code eventCount}.
     */
    Map<String, Object> schedulerStatus(String databaseName);

    /**
     * Generates the DROP EVENT statement for preview and execution.
     *
     * @param databaseName the database name.
     * @param eventName    the event name.
     * @return the DROP EVENT SQL.
     */
    String dropEventSql(String databaseName, String eventName);

    /**
     * Generates the ALTER EVENT ENABLE/DISABLE statement.
     *
     * @param databaseName the database name.
     * @param eventName    the event name.
     * @param enabled      whether to enable (true) or disable (false).
     * @return the ALTER EVENT SQL.
     */
    String setEventEnabledSql(String databaseName, String eventName, boolean enabled);
}
