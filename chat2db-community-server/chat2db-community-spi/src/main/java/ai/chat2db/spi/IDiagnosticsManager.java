package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbStatusResponse;

import java.sql.Connection;

/**
 * Provides database-specific diagnostic inspection.
 */
public interface IDiagnosticsManager {

    InnodbStatusResponse innodbStatus(Connection connection, String databaseVersion);
}
