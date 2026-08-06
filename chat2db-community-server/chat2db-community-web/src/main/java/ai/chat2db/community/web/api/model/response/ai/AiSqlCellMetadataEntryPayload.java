package ai.chat2db.community.web.api.model.response.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiSqlCellMetadataEntryPayload {

    private Integer rowIndex;

    private Integer columnIndex;

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
