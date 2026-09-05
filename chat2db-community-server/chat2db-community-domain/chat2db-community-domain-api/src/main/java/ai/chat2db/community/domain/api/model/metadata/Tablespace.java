package ai.chat2db.community.domain.api.model.metadata;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Represents an InnoDB General Tablespace discovered from {@code INFORMATION_SCHEMA.TABLESPACES}.
 *
 * <p>General tablespaces are instance-level (server-scoped) objects, not owned by any database.
 * Only InnoDB general tablespaces are writable (system, undo, temporary, and NDB tablespaces are
 * read-only or excluded). Data-file paths belong to the MySQL server filesystem and are surfaced
 * verbatim; the application never manages operating-system files.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Tablespace implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonAlias({"NAME", "name"})
    private String name;

    @JsonAlias({"ENGINE", "engine"})
    private String engine;

    @JsonAlias({"SPACE", "space"})
    private Long spaceId;

    /**
     * Data-file path from {@code INFORMATION_SCHEMA.FILES}. Null/empty when the user lacks the
     * {@code PROCESS}/{@code FILE} privilege to read {@code FILES}.
     */
    @JsonAlias({"FILE_NAME", "dataFiles"})
    private List<String> dataFiles;

    @JsonAlias({"FILE_BLOCK_SIZE", "fileBlockSize"})
    private Long fileBlockSize;

    @JsonAlias({"AUTOEXTEND_NEXT_SIZE", "autoextendSize"})
    private Long autoextendSize;

    @JsonAlias({"MAXIMUM_SIZE", "maxSize"})
    private Long maxSize;

    @JsonAlias({"EXTENT_SIZE", "extentSize"})
    private Long extentSize;

    @JsonAlias({"INITIAL_SIZE", "initialSize"})
    private Long initialSize;

    @JsonAlias({"STATUS", "status"})
    private String status;

    @JsonAlias({"COMMENT", "comment"})
    private String comment;

    /**
     * Tables occupying this tablespace (from {@code INFORMATION_SCHEMA.TABLES.TABLESPACE_NAME}),
     * qualified as {@code schema.table}. Populated for the non-empty delete guard; null when not
     * queried (list/detail without occupancy).
     */
    private List<String> occupyingTables;
}
