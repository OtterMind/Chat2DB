package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
public class VersionMetadata {
    @JsonProperty("version")
    String version;
    @JsonProperty("releaseNotes")
    String releaseNotes;
    @JsonProperty("files")
    List<FileInfo> files;
    @JsonProperty("launchCommand")
    List<String> launchCommand;

    public Map<String, FileInfo> getFilesAsMap() {
        if (files == null) return new HashMap<>();
        return files.stream().collect(Collectors.toMap(f -> f.id, f -> f));
    }
}
