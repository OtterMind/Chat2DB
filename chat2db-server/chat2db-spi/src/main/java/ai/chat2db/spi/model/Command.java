package ai.chat2db.spi.model;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class Command {

    /**
     * sql语句
     */
    @NotNull
    private String script;

    /**
     * 原始编辑器中执行脚本的起始行（从1开始）
     */
    private Integer scriptStartLine;

    /**
     * 控制台id
     */
    @NotNull
    private Long consoleId;

    /**
     * 数据源id
     */
    @NotNull
    private Long dataSourceId;

    /**
     * DB名称
     */
    @NotNull
    private String databaseName;


    /**
     * schema名称
     */
    private String schemaName;

    /**
     * 分页编码
     * 只有select语句才有
     */
    private Integer pageNo;

    /**
     * 分页大小
     * 只有select语句才有
     */
    private Integer pageSize;

    /**
     * 返回全部数据
     * 只有select语句才有
     */
    private Boolean pageSizeAll;
}
