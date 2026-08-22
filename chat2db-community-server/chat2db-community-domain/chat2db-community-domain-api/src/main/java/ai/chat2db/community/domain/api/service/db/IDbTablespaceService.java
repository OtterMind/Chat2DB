package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.db.TablespaceCapability;
import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceCreateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceModifyRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceQueryRequest;
import ai.chat2db.community.domain.api.model.sql.Sql;

import java.util.List;

/**
 * Exposes InnoDB General Tablespace discovery and management (create / rename / drop) plus the
 * server-version capability used to gate the rename action.
 */
public interface IDbTablespaceService {

    /**
     * Lists InnoDB General Tablespaces visible to a datasource.
     */
    List<Tablespace> queryAll(DbTablespaceQueryRequest param);

    /**
     * Loads a single tablespace by name, including its occupying tables.
     */
    Tablespace query(DbTablespaceQueryRequest param);

    /**
     * Builds (preview only) the SQL for creating a tablespace.
     */
    Sql createTablespace(DbTablespaceCreateRequest param);

    /**
     * Renames a tablespace (MySQL 8.0+; the manager version-gates this).
     */
    void modifyTablespace(DbTablespaceModifyRequest param);

    /**
     * Reports whether the current server supports {@code ALTER TABLESPACE ... RENAME TO}.
     */
    TablespaceCapability capability(DbTablespaceQueryRequest param);
}
