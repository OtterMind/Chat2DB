package ai.chat2db.community.domain.api.model.metadata;

import java.io.Serializable;

import lombok.Data;

@Data
public class TablePartition implements Serializable {

    private static final long serialVersionUID = 1L;

    private String partitionName;

    private String subpartitionName;

    private Long ordinalPosition;

    private Long subpartitionOrdinalPosition;

    private String method;

    private String subpartitionMethod;

    private String expression;

    private String subpartitionExpression;

    private String description;

    private Long tableRows;

    private Long avgRowLength;

    private Long dataLength;

    private Long maxDataLength;

    private Long indexLength;

    private Long dataFree;

    private String createTime;

    private String updateTime;

    private String checkTime;

    private Long checksum;

    private String comment;

    private String nodegroup;

    private String tablespaceName;
}
