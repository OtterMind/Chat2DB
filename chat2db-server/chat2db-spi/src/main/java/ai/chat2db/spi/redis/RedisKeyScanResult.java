package ai.chat2db.spi.redis;

import lombok.Builder;
import lombok.Data;

/**
 * Redis SCAN final state.
 */
@Data
@Builder
public class RedisKeyScanResult {

    private String cursor;

    private Boolean hasMore;

    private Integer total;
}
