package ai.chat2db.community.domain.api.model.result;

import ai.chat2db.community.domain.api.enums.value.LargeValueTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.beans.Transient;
import java.sql.Blob;
import java.sql.Clob;

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
     * @return {@code true} when the driver handed the value over as a handle instead of transferring its content, so
     *         the value is only read when it is asked for and its cursor has to stay open until then
     */
    @Transient
    public boolean carriesHandle() {
        return rawValue instanceof Clob || rawValue instanceof Blob;
    }

    public static ResultCell of(String value) {
        return ResultCell.builder()
                .value(value)
                .largeValue(false)
                .truncated(false)
                .valueType(LargeValueTypeEnum.UNKNOWN.code())
                .build();
    }
}
