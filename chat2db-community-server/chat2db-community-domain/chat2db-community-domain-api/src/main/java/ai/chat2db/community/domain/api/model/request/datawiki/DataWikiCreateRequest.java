package ai.chat2db.community.domain.api.model.request.datawiki;

import lombok.Data;

@Data
public class DataWikiCreateRequest {

    private String name;

    private String description;

    private Long createdBy;
}
