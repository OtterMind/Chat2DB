package ai.chat2db.community.domain.api.model.datawiki;

import lombok.Data;

@Data
public class DataWikiColumn {

    private String name;

    private String dataType;

    private String sourceComment;

    private String businessName;

    private String businessDescription;

    private String sampleValues;

    private String enumDescription;
}
