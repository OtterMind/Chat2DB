package ai.chat2db.community.web.api.model.request.data.source;

import java.util.List;

import ai.chat2db.community.domain.api.config.DriverConfig;
import jakarta.validation.constraints.NotNull;

import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.community.domain.api.model.datasource.SSHInfo;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;

import lombok.Data;


@Data
public class DataSourceUpdateRequest {


    @NotNull
    private Long id;


    private String alias;


    private String url;


    private String user;


    private String password;


    private String type;


    private String envType;


    private Integer environmentId;


    private String host;


    private String port;


    private SSHInfo ssh;


    private SSLInfo ssl;


    private String sid;


    private String driver;


    private String jdbc;


    private List<KeyValue> extendInfo;


    private DriverConfig driverConfig;


    private String serviceName;


    private String serviceType;


    private String email;


    private String keyfile;


    private String project;


    private Long organizationId;


    private String token;


    private String storageType;


    private Long spaceId;
}
