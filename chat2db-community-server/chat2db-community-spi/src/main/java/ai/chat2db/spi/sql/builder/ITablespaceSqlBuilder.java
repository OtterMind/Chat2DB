package ai.chat2db.spi.sql.builder;

import ai.chat2db.community.domain.api.model.metadata.Tablespace;

/**
 * Builds DDL for InnoDB General Tablespaces.
 *
 * <p>General tablespaces are a MySQL 5.7.6+ InnoDB feature. {@code ALTER TABLESPACE ... RENAME TO}
 * is available only on MySQL 8.0 and later; callers must gate rename on the dialect's
 * {@code supportsTablespaceRename()} capability.
 */
public interface ITablespaceSqlBuilder {

    String buildCreateTablespace(Tablespace tablespace);

    String buildDropTablespace(String tablespaceName);

    /**
     * Renames a tablespace. MySQL 8.0+ only.
     */
    String buildRenameTablespace(String oldTablespaceName, String newTablespaceName);

    /**
     * Adds a data file to an existing tablespace. The data-file path is user-supplied and
     * belongs to the MySQL server filesystem; it is emitted verbatim (escaped) and never
     * validated or canonicalized by the application.
     */
    String buildAlterTablespaceAddDatafile(String tablespaceName, String dataFile);
}
