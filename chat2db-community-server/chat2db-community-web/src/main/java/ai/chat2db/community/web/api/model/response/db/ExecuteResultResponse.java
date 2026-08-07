package ai.chat2db.community.web.api.model.response.db;

import java.util.List;
import java.util.Map;


import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ExecutionContext;
import ai.chat2db.community.domain.api.model.result.ExecutionMetrics;
import ai.chat2db.community.domain.api.model.sql.RefreshTarget;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import lombok.Data;


@Data
public class ExecuteResultResponse {


    private String sql;


    private String originalSql;


    private String description;


    private String message;


    private Boolean success;


    private Integer updateCount;


    private List<Header> headerList;


    private List<List<ResultCell>> dataList;


    private String sqlType;


    private Boolean hasNextPage;


    private Integer pageNo;


    private Integer pageSize;


    private String fuzzyTotal;


    private boolean canEdit;


    private String tableName;


    private Map<String,Object> extra;


    private List<RefreshTarget> refreshTargets;
    private String comment;
    private Integer resultSetId;
    private Integer statementSequence;
    private ExecutionMetrics executionMetrics;
    private ExecutionContext executionContext;


}
