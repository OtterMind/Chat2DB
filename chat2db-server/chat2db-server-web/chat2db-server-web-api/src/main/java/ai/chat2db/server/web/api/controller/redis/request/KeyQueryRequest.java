package ai.chat2db.server.web.api.controller.redis.request;

import ai.chat2db.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

/**
 * @author moji
 * @version ConnectionQueryRequest.java, v 0.1 2022年09月16日 14:23 moji Exp $
 * @date 2022/09/16
 */
@Data
public class KeyQueryRequest extends DataSourceBaseRequest {

    /**
     * 缓存key名称
     */
    private String keyName;

    /**
     * 搜索关键词
     */
    private String searchKey;

    /**
     * Redis SCAN 游标
     */
    private String cursor;

    /**
     * 返回数量
     */
    private Integer count;

    /**
     * 流式返回批大小
     */
    private Integer batchSize;
}
