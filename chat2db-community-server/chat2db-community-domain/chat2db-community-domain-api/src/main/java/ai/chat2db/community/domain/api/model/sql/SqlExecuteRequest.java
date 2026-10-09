package ai.chat2db.community.domain.api.model.sql;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

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

    /**
     * Values for the {@code :name} placeholders in {@link #script}; empty keeps plain
     * execution. Positional {@code ?} values arrive here already renamed to their
     * synthetic {@code __pN} names.
     */
    private Map<String, SqlParameterValue> parameters;

    /**
     * {@code true} when {@link #parameters} came from {@code ?} placeholders, so results
     * report the SQL with {@code ?} as the user wrote it.
     */
    private boolean positionalParameterStyle;

}
