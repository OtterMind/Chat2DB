package ai.chat2db.community.domain.api.model.datawiki;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataWikiDocumentBundle {

    private String dataWikiId;

    private Long revision;

    private String rootDirectory;

    private List<DataWikiDocument> documents = new ArrayList<>();
}
