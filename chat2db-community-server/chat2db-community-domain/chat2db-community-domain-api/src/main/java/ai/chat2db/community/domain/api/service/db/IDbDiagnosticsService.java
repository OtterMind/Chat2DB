package ai.chat2db.community.domain.api.service.db;

/**
 * Exposes database diagnostic inspection contracts.
 */
public interface IDbDiagnosticsService {

    /**
     * Returns the raw InnoDB status output from SHOW ENGINE INNODB STATUS.
     *
     * @return raw status text, or null when not available.
     */
    String innodbStatus();
}
