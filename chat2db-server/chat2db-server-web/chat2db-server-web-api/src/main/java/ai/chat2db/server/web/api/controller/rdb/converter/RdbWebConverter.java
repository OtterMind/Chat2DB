package ai.chat2db.server.web.api.controller.rdb.converter;

import java.util.List;

import ai.chat2db.server.domain.api.param.*;
import ai.chat2db.server.web.api.controller.data.source.vo.DatabaseVO;
import ai.chat2db.server.web.api.controller.rdb.request.*;
import ai.chat2db.server.web.api.controller.rdb.vo.ColumnVO;
import ai.chat2db.server.web.api.controller.rdb.vo.ExecuteResultVO;
import ai.chat2db.server.web.api.controller.rdb.vo.IndexVO;
import ai.chat2db.server.web.api.controller.rdb.vo.MetaSchemaVO;
import ai.chat2db.server.web.api.controller.rdb.vo.SchemaVO;
import ai.chat2db.server.web.api.controller.rdb.vo.SqlVO;
import ai.chat2db.server.web.api.controller.rdb.vo.TableVO;
import ai.chat2db.server.web.api.http.request.EsTableSchemaRequest;
import ai.chat2db.spi.model.Database;
import ai.chat2db.spi.model.ExecuteResult;
import ai.chat2db.spi.model.MetaSchema;
import ai.chat2db.spi.model.Schema;
import ai.chat2db.spi.model.Sql;
import ai.chat2db.spi.model.Table;
import ai.chat2db.spi.model.TableColumn;
import ai.chat2db.spi.model.TableIndex;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * @author moji
 * @version MysqlDataConverter.java, v 0.1 October 14, 2022 14:04 moji Exp $
 * @date 2022/10/14
 */
@Mapper(componentModel = "spring")
public abstract class RdbWebConverter {

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract DlExecuteParam request2param(DmlRequest request);



    public abstract GroupByParam request2param(GroupByRequest request);
    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract OrderByParam request2param(OrderByRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract GroupByParam request2param(GroupByRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract DlExecuteParam request2param(DmlTableRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract DlExecuteParam tableManageRequest2param(DdlRequest request);


    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract DlCountParam request2param(DdlCountRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract TableQueryParam tableRequest2param(TableDetailQueryRequest request);


    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract Table tableRequest2param(TableRequest request);

    /**
     * Parameter conversion
     *
     * @param dto
     * @return
     */
    public abstract SqlVO dto2vo(Sql dto);
    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract TablePageQueryParam tablePageRequest2param(TableBriefQueryRequest request);
    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract TablePageQueryParam tablePageRequest2param(DataExportRequest request);
    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract TableQueryParam tableRequest2param(DataExportRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract ShowCreateTableParam ddlExport2showCreate(DdlExportRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract DropParam tableDelete2dropParam(TableDeleteRequest request);


    /**
     * Model conversion
     *
     * @param dto
     * @return
     */
    public abstract ExecuteResultVO dto2vo(ExecuteResult dto);

    /**
     * Model conversion
     *
     * @param dtos
     * @return
     */
    public abstract List<ExecuteResultVO> dto2vo(List<ExecuteResult> dtos);

    /**
     * Model conversion
     *
     * @param dto
     * @return
     */
    public abstract ColumnVO columnDto2vo(TableColumn dto);

    /**
     * Model conversion
     *
     * @param dtos
     * @return
     */
    public abstract List<ColumnVO> columnDto2vo(List<TableColumn> dtos);

    /**
     * Model conversion
     *
     * @param dto
     * @return
     */
    @Mappings({
        @Mapping(source = "columnList", target = "columnList")
    })
    public abstract IndexVO indexDto2vo(TableIndex dto);

    /**
     * Model conversion
     *
     * @param dtos
     * @return
     */
    public abstract List<IndexVO> indexDto2vo(List<TableIndex> dtos);

    /**
     * Model conversion
     *
     * @param dto
     * @return
     */
    @Mappings({
        @Mapping(source = "columnList", target = "columnList"),
        @Mapping(source = "indexList", target = "indexList"),
    })
    public abstract TableVO tableDto2vo(Table dto);

    /**
     * Model conversion
     *
     * @param dtos
     * @return
     */
    public abstract List<TableVO> tableDto2vo(List<Table> dtos);

    /**
     * Model conversion
     * @param tableColumns
     * @return
     */
    public abstract List<SchemaVO> schemaDto2vo(List<Schema> tableColumns);

    /**
     * Model conversion
     * @param dto
     * @return
     */
    public abstract SchemaVO schemaDto2vo(Schema dto);

    /**
     * Model conversion
     * @param dto
     * @return
     */
    public abstract DatabaseVO databaseDto2vo(Database dto);


    /**
     * Model conversion
     * @param dto
     * @return
     */
    public abstract List<DatabaseVO> databaseDto2vo(List<Database> dto);

    public abstract MetaSchemaVO metaSchemaDto2vo(MetaSchema data);


    public abstract UpdateSelectResultParam request2param(SelectResultUpdateRequest request);

    public abstract TableMilvusQueryRequest request2request(TableBriefQueryRequest request);

    @Mappings({
            @Mapping(source = "databaseName", target = "database"),
            @Mapping(source = "schemaName", target = "schema"),
    })
    public abstract TableVectorParam param2param(TableBriefQueryRequest request);

    public abstract EsTableSchemaRequest req2req(TableBriefQueryRequest request);

    public abstract TablePageQueryParam schemaReq2page(EsTableSchemaRequest request);

    public abstract DmlSqlCopyParam dmlRequest2param(DmlSqlCopyRequest request) ;
}
