package ai.chat2db.spi.model.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventMetadataRequest {

    private String databaseName;

    private String schemaName;

    @NotEmpty
    private String eventName;
}
