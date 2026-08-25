package ai.chat2db.community.domain.api.model.request.datawiki;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiResource;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataWikiUpdateRequest {

    private String id;

    private String name;

    private String description;

    private List<DataWikiResource> resources = new ArrayList<>();

    private Long expectedRevision;
}
