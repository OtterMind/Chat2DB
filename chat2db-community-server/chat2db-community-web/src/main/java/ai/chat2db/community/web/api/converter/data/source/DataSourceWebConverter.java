package ai.chat2db.community.web.api.converter.data.source;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import ai.chat2db.community.domain.api.enums.DataSourceKindEnum;
import ai.chat2db.community.domain.api.model.datasource.DataSource;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePositionUpdateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSourceNamespace;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceCreateRequest;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceQueryRequest;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceTestRequest;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceUpdateRequest;
import ai.chat2db.community.web.api.model.request.data.source.UpdateDatasourcePositionRequest;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceNamespaceResponse;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceResponse;
import ai.chat2db.community.web.api.model.response.data.source.DatabaseResponse;
import ai.chat2db.community.web.api.model.response.data.source.ProgressResponse;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.community.domain.api.model.datasource.SSHInfo;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;


@Slf4j
@Mapper(componentModel = "spring", imports = {DataSourceKindEnum.class})
public abstract class DataSourceWebConverter {

   public static DataSourceWebConverter INSTANCE = Mappers.getMapper(DataSourceWebConverter.class);


    @Mappings({
        @Mapping(target = "user", source = "user")
    })
    public abstract DataSourceResponse dto2response(DataSource dataSource);

    public abstract DataSourceResponse storage2response(WorkspaceDataSource dataSource);

    public abstract WorkspaceDataSource response2storage(DataSourceResponse response);

    public abstract DbDataSourcePageQueryRequest request2param(DataSourceQueryRequest request);

    public abstract DbDataSourcePositionUpdateRequest request2param(UpdateDatasourcePositionRequest request);

    public abstract DataSourceNamespaceResponse storage2response(WorkspaceDataSourceNamespace namespace);

    public abstract DataSourceResponse request2response(DataSourceCreateRequest request);


    public abstract DataSourceResponse request2response(DataSourceUpdateRequest request);


    public abstract DatabaseResponse databaseDto2response(Database databaseDTO);


    public abstract List<DatabaseResponse> databaseDto2response(List<Database> databaseDTOS);


    public abstract DbDataSourcePreConnectRequest testRequest2param(DataSourceTestRequest request);

    public ProgressResponse exportProgress(List<DataSourceResponse> dataSourceResponses) {
        ProgressResponse response = new ProgressResponse();
        int count = dataSourceResponses == null ? 0 : dataSourceResponses.size();
        response.setCount(count);
        response.setMessage(JSON.toJSONString(dataSourceResponses));
        return response;
    }

    public ProgressResponse importProgress() {
        ProgressResponse response = new ProgressResponse();
        response.setMessage("success");
        return response;
    }


    public DataSourceCreateRequest rs2Request(ResultSet rs) throws SQLException {
        String alias = rs.getString("alias");
        String type = rs.getString("type");
        String url1 = rs.getString("url");
        String userName = rs.getString("user_name");
        String password = rs.getString("password");
        String envType = rs.getString("env_type");
        String host = rs.getString("host");
        String port = rs.getString("port");
        String ssh = rs.getString("ssh");
        String ssl = rs.getString("ssl");
        String sid = rs.getString("sid");
        String driver = rs.getString("driver");
        String jdbcUrl = rs.getString("jdbc");
        String driverConfig = rs.getString("driver_config");
        String env = rs.getString("environment_id");
        String kind = rs.getString("kind");
        String serviceName = rs.getString("service_name");
        String serviceType = rs.getString("service_type");
        String extendInfo = rs.getString("extend_info");
        DataSourceCreateRequest request = new DataSourceCreateRequest();
        request.setAlias(alias);
        request.setType(type);
        request.setUrl(url1);
        request.setUser(userName);
        request.setPassword(password);
        request.setHost(host);
        request.setPort(port);
        request.setSsh(StringUtils.isBlank(ssh) ? null : JSON.parseObject(ssh, SSHInfo.class));
        request.setSsl(StringUtils.isBlank(ssl) ? null : JSON.parseObject(ssl, SSLInfo.class));
        request.setSid(sid);
        request.setDriver(driver);
        request.setJdbc(jdbcUrl);
        request.setDriverConfig(StringUtils.isBlank(driverConfig) ? null : JSON.parseObject(driverConfig, DriverConfig.class));
        request.setEnvironmentId(parseEnvironmentId(env));
        request.setServiceName(serviceName);
        request.setServiceType(serviceType);
        request.setExtendInfo(StringUtils.isBlank(extendInfo) ? null : JSON.parseArray(extendInfo, KeyValue.class));
        return request;
    }

    private static Long parseEnvironmentId(String env) {
        if (StringUtils.isBlank(env)) {
            return null;
        }
        try {
            return Long.parseLong(env.trim());
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid environment_id '{}' from legacy datasource row", env);
            return null;
        }
    }

}
