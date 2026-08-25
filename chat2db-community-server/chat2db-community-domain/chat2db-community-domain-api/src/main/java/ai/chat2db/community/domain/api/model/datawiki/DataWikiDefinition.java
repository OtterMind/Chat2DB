package ai.chat2db.community.domain.api.model.datawiki;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class DataWikiDefinition {

    private String id;

    private String name;

    private String description;

    private List<DataWikiResource> resources = new ArrayList<>();

    private Long createdBy;

    private Date gmtCreate;

    private Date gmtModified;

    private Long revision;
}
