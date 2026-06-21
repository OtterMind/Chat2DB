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
 * @since 2023-09-02
 */
@Getter
@Setter
@TableName("DASHBOARD")
public class DashboardDO implements Serializable {

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
     * Dashboard name
     */
    private String name;

    /**
     * Dashboard description
     */
    private String description;

    /**
     * Dashboard layout information
     */
    @TableField("`schema`")
    private String schema;

    /**
     * Whether it has been deleted, y means deleted, n means not deleted
     */
    private String deleted;

    /**
     * user id
     */
    private Long userId;
}
