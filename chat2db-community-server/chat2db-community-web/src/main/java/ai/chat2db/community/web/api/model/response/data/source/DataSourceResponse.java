package ai.chat2db.community.web.api.model.response.data.source;

import java.util.List;

import ai.chat2db.community.web.api.model.response.environment.SimpleEnvironmentResponse;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.community.domain.api.model.datasource.SSHInfo;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import lombok.Data;


@Data
public class DataSourceResponse {


    private Long id;


    private String alias;


    private String url;


    private String user;


    private String password;


    private String authenticationType;


    private String type;


    private String envType;


    private String host;


    private String port;


    private SSHInfo ssh;


    private SSLInfo ssl;


    private String sid;


    private String driver;


    private String jdbc;


    private List<KeyValue> extendInfo;


    private DriverConfig driverConfig;


    private Long environmentId;


    private SimpleEnvironmentResponse environment;


    private String identityColor;


    private Boolean watermarkEnabled;


    private String watermarkContent;


    private String kind;


    private String serviceName;


    private String serviceType;


    private boolean supportDatabase;


    private boolean supportSchema;


    private String email;


    private String keyfile;


    private String project;


    private Long organizationId;


    private String storageType;


    private Long spaceId;
}
