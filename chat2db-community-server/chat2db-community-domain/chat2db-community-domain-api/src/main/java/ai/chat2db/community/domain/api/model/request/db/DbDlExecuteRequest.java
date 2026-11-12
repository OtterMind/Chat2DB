package ai.chat2db.community.domain.api.model.request.db;


import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Internal carrier populated after endpoint-specific request validation.
 */
@Data
public class DbDlExecuteRequest {


    private String sql;


    private Long consoleId;


    private Long applyId;


    private Long dataSourceId;


    private String databaseName;


    private String schemaName;


    private String tableName;


    private Integer pageNo;


    private Integer pageSize;


    private Boolean pageSizeAll;


    private boolean single;


    private Integer resultSetId;

    private Boolean errorContinue;

    private boolean explain;

    /**
     * The editor's SQL parameters switch. When it is on and no values are supplied,
     * SQL that contains placeholders is rejected before anything runs. It never
     * decides whether supplied values are bound.
     */
    private Boolean sqlParameters;

    /**
     * Values for {@code :name} placeholders, keyed by name.
     */
    private Map<String, SqlParameterValue> parameters;

    /**
     * Values for {@code ?} placeholders, in appearance order.
     */
    private List<SqlParameterValue> positionalParameters;
}
