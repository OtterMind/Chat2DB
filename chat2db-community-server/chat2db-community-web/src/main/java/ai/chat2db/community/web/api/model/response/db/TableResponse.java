package ai.chat2db.community.web.api.model.response.db;

import java.util.List;

import ai.chat2db.community.domain.api.model.metadata.CheckConstraintInfo;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import lombok.Data;


@Data
public class TableResponse {


    private String name;


    private String comment;


    private List<TableColumn> columnList;


    private List<TableIndex> indexList;


    private List<CheckConstraintInfo> checkConstraintList;


    private boolean pinned;


    private String ddl;


    private String engine;


    private String charset;


    private String collate;


    private Long incrementValue;


    private String partition;


    private String tablespace;

    private Long rows;

    private Long dataLength;

    private String createTime;

    private String updateTime;

    private String dbVersion;
}
