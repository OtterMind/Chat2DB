package ai.chat2db.community.domain.api.model.datawiki;

import lombok.Data;

@Data
public class DataWikiDocument {

    private String path;

    private String title;

    private String kind;

    private String content;
}
