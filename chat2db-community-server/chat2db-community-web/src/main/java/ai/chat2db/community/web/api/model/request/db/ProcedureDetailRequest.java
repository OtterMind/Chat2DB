package ai.chat2db.community.web.api.model.request.db;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceBaseRequestInfo;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
@Data
public class ProcedureDetailRequest implements IDataSourceBaseRequestInfo {
    @NotNull
    private Long dataSourceId;
    @NotBlank
    @Pattern(regexp = "(?!.*--)[\\p{L}_$][\\p{L}\\p{N}_$-]*")
    private String databaseName;
    @Pattern(regexp = "(?!.*--)[\\p{L}_$][\\p{L}\\p{N}_$-]*")
    private String schemaName;
    @NotBlank
    @Pattern(regexp = "(?!.*--)[\\p{L}_$][\\p{L}\\p{N}_$-]*")
    private String procedureName;
    private boolean refresh;
}
