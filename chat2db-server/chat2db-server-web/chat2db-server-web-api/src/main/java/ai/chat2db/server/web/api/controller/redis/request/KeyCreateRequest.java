package ai.chat2db.server.web.api.controller.redis.request;

import jakarta.validation.constraints.NotNull;

import ai.chat2db.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

/**
 * @author moji
 * @version TableVO.java, v 0.1 2022年09月16日 17:16 moji Exp $
 * @date 2022/09/16
 */
@Data
public class KeyCreateRequest extends DataSourceBaseRequest {

    /**
     * key名称
     */
    @NotNull
    private String name;

    /**
     * key类型
     */
    @NotNull
    private String keyType;

    /**
     * key值
     */
    private Object value;

    /**
     * 过期时间
     */
    private Long ttl;
}
