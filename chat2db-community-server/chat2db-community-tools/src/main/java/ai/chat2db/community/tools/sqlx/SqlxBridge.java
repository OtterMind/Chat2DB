package ai.chat2db.community.tools.sqlx;

import java.util.List;
import java.util.Map;

/**
 * Desktop-side operations on the SQLX command line.
 * <p>
 * The implementation is assembled by the startup module so the JCEF shell can reach it without
 * depending on a domain or storage module.
 */
public interface SqlxBridge {

    /** Current installation state; never performs network access. */
    SqlxStatus status();

    /** Start downloading and installing the given version, or the latest stable release. */
    SqlxStatus install(String operationId, String version);

    /** Start the SQLX self-update for the configured executable. */
    SqlxStatus update(String operationId);

    /** Ask the release channel for the latest stable version and report the outcome. */
    SqlxStatus checkUpdate(String operationId);

    /** Stop a running download; a completed download cannot be cancelled. */
    SqlxStatus cancel(String operationId);

    /** Adopt an executable the user picked, after verifying it is OtterMind SQLX. */
    SqlxStatus useBinary(String path);

    /**
     * Copy the given Chat2DB datasources into SQLX and report what happened.
     * <p>
     * Credentials travel from the JVM to the SQLX process on stdin and are never returned.
     */
    Map<String, Object> importDatasources(List<Long> datasourceIds);

    /**
     * Classify the given Chat2DB datasources against the local SQLX store.
     * <p>
     * Read-only: it opens no datasource connection and never returns credentials.
     */
    List<SqlxDatasourceState> datasourceStates(List<Long> datasourceIds);
}
