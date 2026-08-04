package ai.chat2db.community.domain.api.model.workspace;

import ai.chat2db.community.domain.api.model.datasource.DataSource;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class Namespace {


    private Long id;


    private String name;


    private String description;


    /**
     * Transient hydration of the datasources belonging to this namespace, populated
     * by read paths (DataSourceStorage.getNamespaceDatasource). The source of truth
     * is {@link #datasourceIds}; this field must not be persisted, otherwise read-path
     * mutations would write a stale snapshot back to the namespace JSON file.
     */
    @JsonIgnore
    @JSONField(serialize = false, deserialize = false)
    private List<DataSource> dataSources;


    private List<Long> datasourceIds;


    private Long parentId;
}
