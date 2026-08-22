package ai.chat2db.community.web.api.model.response.db;

import java.util.List;

import lombok.Data;

@Data
public class TablespaceResponse {

    private String name;

    private String engine;

    private Long spaceId;

    private List<String> dataFiles;

    private Long fileBlockSize;

    private Long autoextendSize;

    private Long maxSize;

    private Long extentSize;

    private Long initialSize;

    private String status;

    private String comment;

    private List<String> occupyingTables;
}
