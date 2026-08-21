package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Objects;

@Data
public class FileInfo {
    @JsonProperty("id")
    String id;
    @JsonProperty("serverFileName")
    String serverFileName;
    @JsonProperty("localTargetName")
    String localTargetName;
    @JsonProperty("url")
    String url;
    @JsonProperty("sha256")
    String sha256;
    @JsonProperty("type")
    String type;
    @JsonProperty("extractTo")
    String extractTo;
    @JsonProperty("updateStrategy")
    String updateStrategy;
    @JsonProperty("fileSizeByte")
    long fileSizeByte;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileInfo fileInfo = (FileInfo) o;
        return Objects.equals(id, fileInfo.id) &&
                Objects.equals(sha256, fileInfo.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sha256);
    }
}
