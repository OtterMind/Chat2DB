package ai.chat2db.community.web.api.model.response.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiSqlResultSetPayload {

    private Integer resultIndex;

    private String sqlType;

    private Long durationMs;

    private Integer updateCount;

    private Boolean hasNextPage;

    private Integer rowCount;

    private Integer previewRowCount;

    private Boolean rowsTruncated;

    private List<String> columns = new ArrayList<>();

    private List<List<Object>> rows = new ArrayList<>();

    private List<AiSqlCellMetadataEntryPayload> cellMetadata = new ArrayList<>();
}
