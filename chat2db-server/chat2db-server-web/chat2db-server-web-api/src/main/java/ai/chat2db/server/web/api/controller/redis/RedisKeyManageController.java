package ai.chat2db.server.web.api.controller.redis;

import ai.chat2db.server.tools.base.excption.BusinessException;
import ai.chat2db.server.tools.base.wrapper.result.ActionResult;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.web.api.aspect.ConnectionInfoAspect;
import ai.chat2db.server.web.api.controller.redis.request.KeyCreateRequest;
import ai.chat2db.server.web.api.controller.redis.request.KeyDeleteRequest;
import ai.chat2db.server.web.api.controller.redis.request.KeyQueryRequest;
import ai.chat2db.server.web.api.controller.redis.request.KeyUpdateRequest;
import ai.chat2db.server.web.api.controller.redis.vo.KeyVO;
import ai.chat2db.spi.MetaData;
import ai.chat2db.spi.redis.RedisKeyBrowser;
import ai.chat2db.spi.redis.RedisKeyInfo;
import ai.chat2db.spi.redis.RedisKeyScanResult;
import ai.chat2db.spi.sql.Chat2DBContext;

import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;

/**
 * redis key运维类
 *
 * @author moji
 * @version MysqlTableManageController.java, v 0.1 2022年09月16日 17:41 moji Exp $
 * @date 2022/09/16
 */
@RequestMapping("/api/redis/key")
@RestController
@ConnectionInfoAspect
public class RedisKeyManageController {

    private static final int DEFAULT_KEY_COUNT = 1000;
    private static final long STREAM_TIMEOUT = 30 * 60 * 1000L;

    /**
     * 流式查询当前DB下的key列表
     *
     * @param request
     * @return
     * @throws IOException
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(KeyQueryRequest request) throws IOException {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT);
        emitter.send(SseEmitter.event()
                .name("connect")
                .data(LocalDateTime.now().toString())
                .reconnectTime(3000));

        try {
            RedisKeyBrowser browser = getRedisKeyBrowser();
            int count = request.getCount() == null ? DEFAULT_KEY_COUNT : request.getCount();
            CompletableFuture<RedisKeyScanResult> resultFuture = browser.streamKeys(request.getDatabaseName(),
                    request.getSearchKey(), request.getCursor(), count, batch -> {
                        sendEvent(emitter, "keys", batch.stream().map(this::toVO).toList());
                    });
            resultFuture.whenCompleteAsync((result, throwable) -> {
                if (throwable != null) {
                    Throwable cause = unwrapCompletionException(throwable);
                    sendStreamError(emitter, cause);
                    return;
                }
                try {
                    sendEvent(emitter, "done", Map.of(
                            "total", result.getTotal(),
                            "cursor", result.getCursor(),
                            "hasMore", result.getHasMore()
                    ));
                    emitter.complete();
                } catch (Exception e) {
                    sendStreamError(emitter, e);
                }
            });
        } catch (Exception e) {
            sendStreamError(emitter, e);
        }
        return emitter;
    }

    /**
     * 获取缓存key详情
     *
     * @param request
     * @return
     */
    @GetMapping("/query")
    public DataResult<KeyVO> query(KeyQueryRequest request) {
        RedisKeyInfo keyInfo = getRedisKeyBrowser().queryKey(request.getDatabaseName(), request.getKeyName());
        return DataResult.of(toVO(keyInfo));
    }

    /**
     * 新增Key
     *
     * @param request
     * @return
     */
    @PostMapping("/create")
    public ActionResult create(@RequestBody KeyCreateRequest request) {
        getRedisKeyBrowser().createKey(request.getDatabaseName(), request.getName(), request.getKeyType(),
                request.getValue(), request.getTtl());
        return ActionResult.isSuccess();
    }

    /**
     * 修改key信息
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/update",method = {RequestMethod.POST, RequestMethod.PUT})
    public ActionResult update(@RequestBody KeyUpdateRequest request) {
        Object updateTtl = request.getUpdateTtl();
        Long ttl = updateTtl == null ? null : Long.valueOf(String.valueOf(updateTtl));
        getRedisKeyBrowser().updateKey(request.getDatabaseName(), request.getOriginalKey(), request.getUpdateKey(),
                request.getKeyType(), request.getValue(), ttl);
        return ActionResult.isSuccess();
    }


    /**
     * 删除key
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/delete", method = {RequestMethod.POST, RequestMethod.DELETE})
    public ActionResult delete(@RequestBody KeyDeleteRequest request) {
        getRedisKeyBrowser().deleteKey(request.getDatabaseName(), request.getKeyName());
        return ActionResult.isSuccess();
    }

    private RedisKeyBrowser getRedisKeyBrowser() {
        MetaData metaData = Chat2DBContext.getMetaData();
        if (metaData instanceof RedisKeyBrowser browser) {
            return browser;
        }
        throw new BusinessException("当前数据源不是 Redis");
    }

    private KeyVO toVO(RedisKeyInfo keyInfo) {
        KeyVO vo = new KeyVO();
        BeanUtils.copyProperties(keyInfo, vo);
        return vo;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            throw new BusinessException("Redis key stream send failed", null, e);
        }
    }

    private void sendStreamError(SseEmitter emitter, Throwable throwable) {
        try {
            sendEvent(emitter, "redis_error", Map.of("message", throwable.getMessage()));
        } catch (Exception ignored) {
            // ignore send failure and complete the emitter with the original error
        }
        emitter.completeWithError(throwable);
    }

    private Throwable unwrapCompletionException(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
