package ai.chat2db.server.web.api.controller.redis;

import ai.chat2db.server.tools.base.excption.BusinessException;
import ai.chat2db.server.tools.base.wrapper.result.ActionResult;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.tools.base.wrapper.result.ListResult;
import ai.chat2db.server.web.api.aspect.ConnectionInfoAspect;
import ai.chat2db.server.web.api.controller.redis.request.KeyCreateRequest;
import ai.chat2db.server.web.api.controller.redis.request.KeyDeleteRequest;
import ai.chat2db.server.web.api.controller.redis.request.KeyQueryRequest;
import ai.chat2db.server.web.api.controller.redis.request.KeyUpdateRequest;
import ai.chat2db.server.web.api.controller.redis.vo.KeyVO;
import ai.chat2db.spi.MetaData;
import ai.chat2db.spi.redis.RedisKeyBrowser;
import ai.chat2db.spi.redis.RedisKeyInfo;
import ai.chat2db.spi.sql.Chat2DBContext;

import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * 查询当前DB下的key列表
     *
     * @param request
     * @return
     */
    @GetMapping("/list")
    public ListResult<KeyVO> list(KeyQueryRequest request) {
        RedisKeyBrowser browser = getRedisKeyBrowser();
        int count = request.getCount() == null ? DEFAULT_KEY_COUNT : request.getCount();
        return ListResult.of(browser.listKeys(request.getDatabaseName(), request.getSearchKey(), count).stream()
                .map(this::toVO)
                .toList());
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
        return null;
    }

    /**
     * 修改key信息
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/update",method = {RequestMethod.POST, RequestMethod.PUT})
    public ActionResult update(@RequestBody KeyUpdateRequest request) {
        return null;
    }


    /**
     * 删除key
     *
     * @param request
     * @return
     */
    @DeleteMapping("/delete")
    public ActionResult delete(@RequestBody KeyDeleteRequest request) {
        return null;
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
}
