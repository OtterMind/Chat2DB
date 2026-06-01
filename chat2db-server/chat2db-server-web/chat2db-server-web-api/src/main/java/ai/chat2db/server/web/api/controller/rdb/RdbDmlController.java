package ai.chat2db.server.web.api.controller.rdb;

import java.sql.Connection;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import ai.chat2db.server.domain.api.param.DlExecuteParam;
import ai.chat2db.server.domain.api.param.OrderByParam;
import ai.chat2db.server.domain.api.param.UpdateSelectResultParam;
import ai.chat2db.server.domain.api.service.DlTemplateService;
import ai.chat2db.server.domain.api.service.ForeignKeySyncService;
import ai.chat2db.server.domain.core.service.VirtualFkSuggestionService;
import ai.chat2db.server.tools.base.enums.DataSourceTypeEnum;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.tools.base.wrapper.result.ListResult;
import ai.chat2db.server.web.api.aspect.ConnectionInfoAspect;
import ai.chat2db.server.web.api.controller.rdb.converter.RdbWebConverter;
import ai.chat2db.server.web.api.controller.rdb.request.DdlCountRequest;
import ai.chat2db.server.web.api.controller.rdb.request.DmlRequest;
import ai.chat2db.server.web.api.controller.rdb.request.DmlTableRequest;
import ai.chat2db.server.web.api.controller.rdb.request.OrderByRequest;
import ai.chat2db.server.web.api.controller.rdb.request.SelectResultUpdateRequest;
import ai.chat2db.server.web.api.controller.rdb.vo.ExecuteResultVO;
import ai.chat2db.spi.MetaData;
import ai.chat2db.spi.model.ExecuteResult;
import ai.chat2db.spi.model.Table;
import ai.chat2db.spi.model.VirtualForeignKey;
import ai.chat2db.spi.model.VirtualForeignKeySuggestion;
import ai.chat2db.spi.sql.Chat2DBContext;

/**
 * mysql数据运维类
 *
 * @author moji
 * @version MysqlDataManageController.java, v 0.1 2022年09月16日 17:37 moji Exp $
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
    private ForeignKeySyncService foreignKeySyncService;

    @Autowired
    private VirtualFkSuggestionService virtualFkSuggestionService;


    /**
     * 执行SQL语句，返回所有执行结果
     * 适用于执行多条SQL语句，需要查看所有执行结果的场景
     *
     * @param request SQL执行请求
     * @return 所有SQL语句的执行结果列表
     */
    @RequestMapping(value = "/execute", method = {RequestMethod.POST, RequestMethod.PUT})
    public ListResult<ExecuteResultVO> manage(@RequestBody DmlRequest request) {
        DlExecuteParam param = rdbWebConverter.request2param(request);
        List<ExecuteResult> resultList = dlTemplateService.execute(param);

        // Add Virtual FK suggestions using cached JSqlParser AST
        if (!resultList.isEmpty()) {
            ExecuteResult firstResult = resultList.get(0);
            if (canSuggestVirtualForeignKeys(firstResult)) {
                List<VirtualForeignKey> existingFKs = foreignKeySyncService.listAllForeignKeys(
                        request.getDataSourceId(),
                        request.getDatabaseName(),
                        request.getSchemaName(),
                        null
                ).stream()
                        .filter(fk -> fk instanceof VirtualForeignKey)
                        .map(fk -> (VirtualForeignKey) fk)
                        .toList();
                
                List<VirtualForeignKeySuggestion> suggestions = virtualFkSuggestionService.suggest(firstResult.getJsqlStatement(), existingFKs);
                if (!suggestions.isEmpty()) {
                    firstResult.setVkSuggestions(suggestions);
                }
            }
        }
        List<ExecuteResultVO> resultVOS = rdbWebConverter.dto2vo(resultList);
        return ListResult.of(resultVOS);
    }

    private boolean canSuggestVirtualForeignKeys(ExecuteResult result) {
        return result != null
                && Boolean.TRUE.equals(result.getSuccess())
                && result.getJsqlStatement() != null
                && !CollectionUtils.isEmpty(result.getDataList());
    }



    /**
     * 查询表结构信息
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/execute_table", method = {RequestMethod.POST, RequestMethod.PUT})
    public ListResult<ExecuteResultVO> executeTable(@RequestBody DmlTableRequest request) {
        DlExecuteParam param = rdbWebConverter.request2param(request);
        // 解析sql
        String type = Chat2DBContext.getConnectInfo().getDbType();
        if (DataSourceTypeEnum.REDIS.getCode().equals(type)) {
            MetaData metaData = Chat2DBContext.getMetaData();
            List<Table> tables = metaData.tables(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(), request.getTableName());
            for (Table table : tables) {
                if ("string".equals(table.getType())) {
                    param.setSql("GET " + request.getTableName());
                } else if ("hash".equals(table.getType())) {
                    param.setSql("HGETALL " + request.getTableName());
                } else if ("list".equals(table.getType())) {
                    param.setSql("LRANGE " + request.getTableName() + " 0 -1");
                } else if ("set".equals(table.getType())){
                    param.setSql("SMEMBERS " + request.getTableName());
                } else if ("zset".equals(table.getType())){
                    param.setSql("ZRANGE " + request.getTableName() + " 0 -1");
                } else if ("stream".equals(table.getType())){
                    param.setSql("XRANGE " + request.getTableName() + " 0 -1");
                }
            }
        } else if (DataSourceTypeEnum.MONGODB.getCode().equals(type)) {
            param.setSql("db." + request.getTableName() + ".find()");
        } else if (DataSourceTypeEnum.PHOENIX.getCode().equals(type)) {
            MetaData metaData = Chat2DBContext.getMetaData();
            // 拼接`tableName`，避免关键字被占用问题
            param.setSql("select * from " + metaData.getMetaDataName(request.getSchemaName(),request.getTableName()));
        } else {
            MetaData metaData = Chat2DBContext.getMetaData();
            // 拼接`tableName`，避免关键字被占用问题
            param.setSql("select * from " + metaData.getMetaDataName(request.getTableName()));
        }
        List<ExecuteResult> resultList = dlTemplateService.execute(param);
        List<ExecuteResultVO> resultVOS = rdbWebConverter.dto2vo(resultList);
        return ListResult.of(resultVOS);
    }

    /**
     * update 查询结果
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/execute_update", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<ExecuteResultVO> executeSelectResultUpdate(@RequestBody DmlRequest request) {
        DlExecuteParam param = rdbWebConverter.request2param(request);
        ExecuteResult result = dlTemplateService.executeUpdate(param);
        if (result == null || Boolean.FALSE.equals(result.getSuccess())) {
            return DataResult.error("EXECUTE_ERROR", result != null ? result.getMessage() : "Unknown error");
        }
        ExecuteResultVO executeResultVO = rdbWebConverter.dto2vo(result);
        return DataResult.of(executeResultVO);

    }

    @RequestMapping(value = "/get_update_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> getUpdateSelectResultSql(@RequestBody SelectResultUpdateRequest request) {
        UpdateSelectResultParam param = rdbWebConverter.request2param(request);
        return DataResult.of(dlTemplateService.updateSelectResult(param));
    }


    @RequestMapping(value = "/get_order_by_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> getOrderBySql(@RequestBody OrderByRequest request) {

        OrderByParam param = rdbWebConverter.request2param(request);

        return DataResult.of(dlTemplateService.getOrderBySql(param));
    }

    /**
     * 执行SQL语句，返回单个执行结果
     * 成功时返回第一个成功结果，失败时返回第一个失败结果
     * 适用于执行DDL语句或单条SQL语句的场景
     *
     * @param request SQL执行请求
     * @return 单个执行结果（成功时返回第一个，失败时返回第一个失败）
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
                List<ExecuteResult> resultList = dlTemplateService.execute(param);
                List<ExecuteResultVO> resultVOS = rdbWebConverter.dto2vo(resultList);
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
     * 统计行的数量
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/count", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<Long> count(@RequestBody DdlCountRequest request) {
        return DataResult.of(dlTemplateService.count(rdbWebConverter.request2param(request)));
    }

}
