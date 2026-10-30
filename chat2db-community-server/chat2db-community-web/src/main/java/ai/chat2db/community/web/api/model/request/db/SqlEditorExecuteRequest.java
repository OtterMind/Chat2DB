package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceSchemaRequestInfo;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SqlEditorExecuteRequest implements IDataSourceSchemaRequestInfo {

    @NotNull
    private Long dataSourceId;

    private String databaseName;

    private String schemaName;

    @NotBlank
    private String sql;

    private Long consoleId;

    private Long applyId;

    @Min(1)
    private Integer pageNo;

    @Min(1)
    private Integer pageSize;

    private boolean single;

    private Integer resultSetId;

    private Boolean errorContinue;

    private boolean explain;

    /**
     * The editor's SQL parameters switch. It decides whether placeholders are
     * recognised without values; supplied values are always bound.
     */
    private Boolean sqlParameters;

    /**
     * Typed values for the {@code :name} placeholders in {@link #sql}, keyed by name.
     */
    private Map<String, SqlParameterValue> parameters;

    /**
     * Typed values for the {@code ?} placeholders in {@link #sql}, in appearance order.
     */
    private List<SqlParameterValue> positionalParameters;
}
