package ai.chat2db.community.domain.api.model.operation;


import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;


@Data
public class OperationLog {


    private Long id;


    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private String gmtCreate;


    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private String gmtModified;


    private String name;


    private Long dataSourceId;


    private String dataSourceName;


    private Boolean connectable;


    private String databaseName;


    private String type;


    private String operationType;


    private String ddl;

    private Boolean more = false;

    private String status;


    private Long operationRows;


    private Long useTime;


    private String extendInfo;


    private String schemaName;


    private Long organizationId;


    private String userName;
}
