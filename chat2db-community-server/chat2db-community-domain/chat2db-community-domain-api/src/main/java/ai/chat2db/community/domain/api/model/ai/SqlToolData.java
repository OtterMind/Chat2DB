package ai.chat2db.community.domain.api.model.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SqlToolData {

    private List<ResultSet> results = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResultSet {
        private Integer resultIndex;
        private Boolean success;
        private String sqlType;
        private Long durationMs;
        private Integer updateCount;
        private String message;
        private String description;
        private Boolean hasNextPage;
        private Integer rowCount;
        private Integer previewRowCount;
        private Boolean rowsTruncated;
        private List<String> columns = new ArrayList<>();
        private List<List<Object>> rows = new ArrayList<>();
        private List<List<CellMetadata>> rowCellMetadata = new ArrayList<>();
        private String text;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CellMetadata {
        private Boolean rawValueAvailable;
        private Object displayValue;
        private Boolean largeValue;
        private String largeValueId;
        private String valueType;
        private Integer sqlType;
        private String columnType;
        private Long sizeBytes;
        private Long sizeChars;
        private Long loadedBytes;
        private Long loadedChars;
        private Boolean truncated;
        private String unsupportedReason;
        private String rawValueUnavailableReason;
    }
}
