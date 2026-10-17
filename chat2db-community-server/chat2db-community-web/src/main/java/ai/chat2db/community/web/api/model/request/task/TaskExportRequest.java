package ai.chat2db.community.web.api.model.request.task;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TaskExportRequest extends DataSourceBaseRequest {

    private String taskType;

    private String taskName;

    private List<String> tableNames;

    private String sql;

    private String originalSql;

    private Integer resultSetId;

    private String exportSize;

    private String format;

    private String scope;

    private Boolean containData;

    private Boolean containsHeader;

    private String exportPath;

    private String suggestedFileName;

    /**
     * SQL parameter values carried over from a parameterised result. Export does
     * not support parameters yet, so their presence alone rejects the request;
     * they are deliberately untyped so any shape gets that answer.
     */
    private Map<String, Object> parameters;

    /**
     * Positional SQL parameter values carried over from a parameterised result.
     */
    private List<Object> positionalParameters;

    public boolean hasSqlParameters() {
        return (parameters != null && !parameters.isEmpty())
                || (positionalParameters != null && !positionalParameters.isEmpty());
    }
}
