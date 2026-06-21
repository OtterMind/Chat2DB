package ai.chat2db.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * Data source connection table
 * </p>
 *
 * @author chat2db
 * @since 2023-08-26
 */
@Getter
@Setter
@TableName("DATA_SOURCE")
public class DataSourceDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * primary key
     */
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    /**
     * creation time
     */
    private Date gmtCreate;

    /**
     * modified time
     */
    private Date gmtModified;

    /**
     * Alias
     */
    private String alias;

    /**
     * connection address
     */
    private String url;

    /**
     * userName
     */
    private String userName;

    /**
     * password
     */
    private String password;

    /**
     * Database type
     */
    private String type;

    /**
     * environment type
     */
    private String envType;

    /**
     * user id
     */
    private Long userId;

    /**
     * host address
     */
    private String host;

    /**
     * port
     */
    private String port;

    /**
     * ssh configuration information json
     */
    private String ssh;

    /**
     * ssl configuration information json
     */
    @TableField("`ssl`")
    private String ssl;

    /**
     * sid
     */
    private String sid;

    /**
     * driver information
     */
    private String driver;

    /**
     * jdbc version
     */
    private String jdbc;

    /**
     * Custom extension field json
     */
    private String extendInfo;

    /**
     * driver_config configuration
     */
    private String driverConfig;

    /**
     * environment id
     */
    private Long environmentId;

    /**
     * Connection Type
     */
    private String kind;

    /**
     * service name
     */
    private String serviceName;

    /**
     * Service type
     */
    private String serviceType;

}
