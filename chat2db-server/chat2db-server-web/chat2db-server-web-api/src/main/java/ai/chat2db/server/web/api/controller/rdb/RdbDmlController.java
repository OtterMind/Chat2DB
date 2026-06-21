package ai.chat2db.server.web.api.controller.rdb;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.chat2db.server.domain.api.model.Config;
import ai.chat2db.server.domain.api.param.DlExecuteParam;
import ai.chat2db.server.domain.api.param.GroupByParam;
import ai.chat2db.server.domain.api.param.OrderByParam;
import ai.chat2db.server.domain.api.param.UpdateSelectResultParam;
import ai.chat2db.server.domain.api.service.ConfigService;
import ai.chat2db.server.domain.api.service.DlTemplateService;
import ai.chat2db.server.tools.base.enums.DataSourceTypeEnum;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.tools.base.wrapper.result.ListResult;
import ai.chat2db.server.tools.common.util.ConfigUtils;
import ai.chat2db.server.web.api.aspect.ConnectionInfoAspect;
import ai.chat2db.server.web.api.controller.ai.chat2db.client.Chat2dbAIClient;
import ai.chat2db.server.web.api.controller.rdb.converter.RdbWebConverter;
import ai.chat2db.server.web.api.controller.rdb.request.*;
import ai.chat2db.server.web.api.controller.rdb.vo.ExecuteResultVO;
import ai.chat2db.server.web.api.http.GatewayClientService;
import ai.chat2db.server.web.api.http.request.SqlExecuteHistoryCreateRequest;
import ai.chat2db.server.web.api.util.ApplicationContextUtil;
import ai.chat2db.spi.MetaData;
import ai.chat2db.spi.model.ExecuteResult;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * mysql data operation and maintenance class
 *
 * @author moji
 * @version MysqlDataManageController.java, v 0.1 September 16, 2022 17:37 moji Exp $
 * @date 2022/09/16
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/dml")
@RestController
public class RdbDmlController {

    @Autowired
    private RdbWebConverter rdbWebConverter;

    @Autowired
    private DlTemplateService dlTemplateService;

    @Autowired
    private GatewayClientService gatewayClientService;

    public static ExecutorService executorService = Executors.newFixedThreadPool(10);

    /**
     * Data operation and maintenance such as addition, deletion, modification and query
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/execute", method = {RequestMethod.POST, RequestMethod.PUT})
    public ListResult<ExecuteResultVO> manage(@RequestBody DmlRequest request) {
        DlExecuteParam param = rdbWebConverter.request2param(request);
        ListResult<ExecuteResult> resultDTOListResult = dlTemplateService.execute(param);
        List<ExecuteResultVO> resultVOS = rdbWebConverter.dto2vo(resultDTOListResult.getData());
        return ListResult.of(resultVOS);
    }


    /**
     * query chat2db apikey
     *
     * @return
     */
    private String getClientId() {
        ConfigService configService = ApplicationContextUtil.getBean(ConfigService.class);
        Config keyConfig = configService.find(Chat2dbAIClient.CHAT2DB_OPENAI_KEY).getData();
        if (Objects.isNull(keyConfig) || StringUtils.isBlank(keyConfig.getContent())) {
            return ConfigUtils.getClientId();
        }
        return keyConfig.getContent();
    }

    /**
     * Query table structure information
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/execute_table", method = {RequestMethod.POST, RequestMethod.PUT})
    public ListResult<ExecuteResultVO> executeTable(@RequestBody DmlTableRequest request) {
        DlExecuteParam param = rdbWebConverter.request2param(request);
        return dlTemplateService.executeSelectTable(param)
                .map(rdbWebConverter::dto2vo);
    }

    /**
     * update search result
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/execute_update", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<ExecuteResultVO> executeSelectResultUpdate(@RequestBody DmlRequest request) {
        DlExecuteParam param = rdbWebConverter.request2param(request);
        DataResult<ExecuteResult> result = dlTemplateService.executeUpdate(param);
        if (!result.success()) {
            return DataResult.error(result.getErrorCode(), result.getErrorMessage());
        }
        ExecuteResultVO executeResultVO = rdbWebConverter.dto2vo(result.getData());
        return DataResult.of(executeResultVO);

    }

    @RequestMapping(value = "/get_update_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> getUpdateSelectResultSql(@RequestBody SelectResultUpdateRequest request) {
        UpdateSelectResultParam param = rdbWebConverter.request2param(request);
        return dlTemplateService.updateSelectResult(param);
    }


    @RequestMapping(value = "/get_group_by_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> getGroupBySql(@RequestBody GroupByRequest request) {
        GroupByParam param = rdbWebConverter.request2param(request);
        return dlTemplateService.getGroupBySql(param);
    }

    @RequestMapping(value = "/get_order_by_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> getOrderBySql(@RequestBody OrderByRequest request) {
        OrderByParam param = rdbWebConverter.request2param(request);
        return dlTemplateService.getOrderBySql(param);
    }

    /**
     * Data operation and maintenance such as addition, deletion, modification and query
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/execute_ddl", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<ExecuteResultVO> executeDDL(@RequestBody DmlRequest request) {
        DlExecuteParam param = rdbWebConverter.request2param(request);
        Connection connection = Chat2DBContext.getConnection();
        if (connection != null) {
            try {
                boolean flag = true;
                ExecuteResultVO executeResult = null;
                //connection.setAutoCommit(false);
                ListResult<ExecuteResult> resultDTOListResult = dlTemplateService.execute(param);
                List<ExecuteResultVO> resultVOS = rdbWebConverter.dto2vo(resultDTOListResult.getData());
                if (!CollectionUtils.isEmpty(resultVOS)) {
                    for (ExecuteResultVO resultVO : resultVOS) {
                        if (!resultVO.getSuccess()) {
                            flag = false;
                            executeResult = resultVO;
                            break;

                        }
                    }
                }
                if (flag) {
                    //connection.commit();
                    return DataResult.of(resultVOS.get(0));
                } else {
                    //connection.rollback();
                    return DataResult.of(executeResult);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        } else {
            return DataResult.error("connection error", "");
        }
    }

    /**
     * Number of statistics rows
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/count", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<Long> count(@RequestBody DdlCountRequest request) {
        return dlTemplateService.count(rdbWebConverter.request2param(request));
    }

}
