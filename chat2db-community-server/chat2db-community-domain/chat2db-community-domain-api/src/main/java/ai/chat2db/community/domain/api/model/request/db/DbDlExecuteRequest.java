package ai.chat2db.community.domain.api.model.request.db;


import lombok.Data;

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
}
