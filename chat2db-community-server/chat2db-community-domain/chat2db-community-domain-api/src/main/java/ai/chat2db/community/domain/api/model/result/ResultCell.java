package ai.chat2db.community.domain.api.model.result;

import ai.chat2db.community.domain.api.enums.value.LargeValueTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.beans.Transient;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultCell {

    private String value;

    private transient Object rawValue;

    private boolean largeValue;

    private String largeValueId;

    private String valueType;

    private Integer sqlType;

    private String columnType;

    private Long sizeBytes;

    private Long sizeChars;

    private Long loadedBytes;

    private Long loadedChars;

    private boolean truncated;

    private String unsupportedReason;

    @Transient
    public Object getRawValue() {
        return rawValue;
    }

    /**
     * Builds a string-only cell where value is the display/serialized value and rawValue is the same stable
     * string for structured AI/tool consumers that need lossless positional rows.
     */
    public static ResultCell of(String value) {
        return ResultCell.builder()
                .value(value)
                .rawValue(value)
                .largeValue(false)
                .truncated(false)
                .valueType(LargeValueTypeEnum.UNKNOWN.code())
                .build();
    }
}
