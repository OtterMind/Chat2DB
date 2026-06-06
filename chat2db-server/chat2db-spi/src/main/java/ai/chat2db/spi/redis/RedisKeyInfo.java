package ai.chat2db.spi.redis;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RedisKeyInfo {

    private String name;

    private Object value;

    private String type;

    private Long ttl;

    private Long size;
}
