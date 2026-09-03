package ai.chat2db.community.domain.api.model.metadata;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Date;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    private String databaseName;

    private String schemaName;

    private String eventName;

    private String definer;

    private String timeZone;

    private String eventType;

    private Date executeAt;

    private String intervalValue;

    private String intervalField;

    private Date starts;

    private Date ends;

    private String status;

    private String onCompletion;

    private String comment;

    private String definition;

    private String eventBody;
}
