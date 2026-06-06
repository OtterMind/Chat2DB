package ai.chat2db.server.web.api.controller.redis.vo;

import lombok.Data;

/**
 * @author moji
 * @version Table.java, v 0.1 2022年09月16日 17:16 moji Exp $
 * @date 2022/09/16
 */
@Data
public class KeyVO {

    /**
     * key名称
     */
    private String name;

    /**
     * key值
     */
    private Object value;

    /**
     * key类型
     */
    private String type;

    /**
     * 过期时间
     */
    private Long ttl;

    /**
     * key 内存占用字节数
     */
    private Long size;
}
