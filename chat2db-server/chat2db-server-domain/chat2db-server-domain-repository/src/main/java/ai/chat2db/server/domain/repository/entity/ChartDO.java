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
 * Custom dashboard
 * </p>
 *
 * @author chat2db
 * @since 2023-09-09
 */
@Getter
@Setter
@TableName("CHART")
public class ChartDO implements Serializable {

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
     * Chart name
     */
    private String name;

    /**
     * Chart description
     */
    private String description;

    /**
     * chart information
     */
    @TableField("`schema`")
    private String schema;

    /**
     * Data source connection ID
     */
    private Long dataSourceId;

    /**
     * Database type
     */
    private String type;

    /**
     * DB name
     */
    private String databaseName;


    private String schemaName;

    /**
     * DDL content
     */
    private String ddl;

    /**
     * Whether it has been deleted, 'Y' means deleted, 'N' means not deleted
     */
    private String deleted;

    /**
     * user id
     */
    private Long userId;
}
