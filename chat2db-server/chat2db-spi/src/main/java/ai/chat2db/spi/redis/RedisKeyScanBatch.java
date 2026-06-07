package ai.chat2db.spi.redis;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Redis SCAN batch payload.
 */
@Data
@Builder
public class RedisKeyScanBatch {

    private List<RedisKeyInfo> items;

    private String cursor;

    private Boolean hasMore;

    private Integer total;
}
