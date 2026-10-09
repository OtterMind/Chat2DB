package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import jakarta.validation.constraints.NotNull;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;

import lombok.Data;

import java.util.List;
import java.util.Map;


@Data
public class DdlCountRequest extends DataSourceBaseRequest implements IDataSourceConsoleRequestInfo {


    @NotNull
    private String sql;


    @NotNull
    private Long consoleId;


    @NotNull
    private String tableName;

    /**
     * Values of the result being counted, for the {@code :name} placeholders in {@link #sql}.
     */
    private Map<String, SqlParameterValue> parameters;

    /**
     * Values of the result being counted, for the {@code ?} placeholders in {@link #sql}.
     */
    private List<SqlParameterValue> positionalParameters;
}
