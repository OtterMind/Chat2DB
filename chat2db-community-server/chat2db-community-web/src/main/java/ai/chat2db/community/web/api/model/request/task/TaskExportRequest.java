package ai.chat2db.community.web.api.model.request.task;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import lombok.Data;

import java.util.List;

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

    private String compression;

    private String scope;

    private Boolean containData;

    private Boolean containsHeader;

    private String exportPath;

    private String suggestedFileName;

    /**
     * Optional rows-per-checkpoint interval enabling resumable exports.
     */
    private Integer checkpointRows;
}
