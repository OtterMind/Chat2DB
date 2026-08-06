package ai.chat2db.community.domain.api.model.result;

import ai.chat2db.community.domain.api.model.sql.RefreshTarget;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.util.List;
import java.util.Map;


@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteResponse {




    private Boolean success;




    private String message;




    private String sql;




    private String originalSql;




    private String description;




    private Integer updateCount;




    private List<Header> headerList;




    private List<List<ResultCell>> dataList;

    public List<List<String>> getDisplayDataList() {
        if (dataList == null) {
            return null;
        }
        return dataList.stream()
                .map(row -> row == null ? null : row.stream()
                        .map(cell -> cell == null ? null : cell.getValue())
                        .toList())
                .toList();
    }




    private String sqlType;




    private Boolean hasNextPage;




    private Integer pageNo;




    private Integer pageSize;




    private String fuzzyTotal;




    private boolean canEdit;




    private String tableName;




    private Map<String, Object> extra;
    private List<RefreshTarget> refreshTargets;
    private String comment;
    private Integer resultSetId;
    private Integer statementSequence;
    private ExecutionMetrics executionMetrics;
    private ExecutionContext executionContext;
}
