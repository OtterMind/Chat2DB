package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbStatusResponse;

/**
 * Exposes database diagnostic inspection contracts.
 */
public interface IDbDiagnosticsService {

    /**
     * Returns parsed InnoDB status output from SHOW ENGINE INNODB STATUS.
     *
     * @return status response containing complete raw text and best-effort structured diagnostics.
     */
    InnodbStatusResponse innodbStatus();
}
