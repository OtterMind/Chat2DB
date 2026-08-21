package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LatestVersionInfo {
    @JsonProperty("latestVersion")
    String latestVersion;
    @JsonProperty("metadataUrl")
    String metadataUrl;
    @JsonProperty("forceUpdate")
    Boolean forceUpdate = Boolean.FALSE;
}
