package ai.chat2db.community.domain.api.model.sql;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SqlExecuteRequest {




    @NotNull
    private String script;




    @NotNull
    private Long consoleId;




    @NotNull
    private Long dataSourceId;




    @NotNull
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

    /** Internal opt-in used by Agent v2; ordinary queries retain display previews. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @com.alibaba.fastjson2.annotation.JSONField(serialize = false, deserialize = false)
    private boolean fullResultValues;

}
