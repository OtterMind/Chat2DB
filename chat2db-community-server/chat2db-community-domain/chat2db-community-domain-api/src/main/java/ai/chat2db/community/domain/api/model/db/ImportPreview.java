package ai.chat2db.community.domain.api.model.db;

import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportPreview {

    private List<String> sourceColumns;

    private List<List<String>> previewData;

    private String targetTableName;

    private List<ImportTargetColumn> targetColumns;

    private List<ImportColumnMapping> suggestedMapping;

    private int previewLimit;
}
