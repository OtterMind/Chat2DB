package ai.chat2db.community.domain.core.converter;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.community.domain.api.model.cli.CliSqlColumn;
import ai.chat2db.community.domain.api.model.cli.CliSqlQueryResponse;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.request.cli.CliSqlQueryRequest;
import ai.chat2db.community.tools.util.I18nUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class CliSqlDomainConverter {

    public CliSqlQueryResponse emptyQueryResponse(CliSqlQueryRequest request) {
        CliSqlQueryResponse response = new CliSqlQueryResponse();
        response.setColumns(Collections.emptyList());
        response.setRows(Collections.emptyList());
        response.setRowCount(0);
        response.setHasNextPage(Boolean.FALSE);
        response.setTruncated(Boolean.FALSE);
        response.setPageNo(request.safePageNo());
        response.setPageSize(request.safePageSize());
        return response;
    }

    public CliSqlQueryResponse executeResult2response(ExecuteResponse result, CliSqlQueryRequest request) {
        List<Header> headers = result.getHeaderList();
        List<List<ResultCell>> resultRows = result.getDataList() == null ? Collections.emptyList() : result.getDataList();
        List<List<String>> rows = cellRows2stringRows(resultRows);
        if (!includeRowNumber(request)) {
            rows = stripRowNumberRows(headers, rows);
            headers = stripRowNumberHeader(headers);
        }
        CliSqlQueryResponse response = new CliSqlQueryResponse();
        response.setColumns(header2response(headers));
        response.setRows(rows);
        response.setUpdateCount(result.getUpdateCount());
        response.setRowCount(result.getUpdateCount() == null ? rows.size() : result.getUpdateCount());
        response.setHasNextPage(Boolean.TRUE.equals(result.getHasNextPage()));
        response.setTruncated(Boolean.TRUE.equals(result.getHasNextPage()));
        response.setDuration(result.getExecutionMetrics() == null
                ? null
                : result.getExecutionMetrics().getTotalDurationMs());
        response.setPageNo(result.getPageNo() == null ? request.safePageNo() : result.getPageNo());
        response.setPageSize(result.getPageSize() == null ? request.safePageSize() : result.getPageSize());
        response.setResultSetId(result.getResultSetId() == null ? null : String.valueOf(result.getResultSetId()));
        return response;
    }

    public List<CliSqlColumn> header2response(List<Header> headers) {
        if (headers == null) {
            return Collections.emptyList();
        }
        return headers.stream().filter(Objects::nonNull).map(this::header2response).toList();
    }

    public CliSqlColumn header2response(Header header) {
        CliSqlColumn response = new CliSqlColumn();
        response.setName(StringUtils.defaultIfBlank(header.getColumnName(), header.getName()));
        response.setType(StringUtils.defaultIfBlank(header.getColumnType(), header.getDataType()));
        response.setTableName(header.getTableName());
        return response;
    }

    private boolean includeRowNumber(CliSqlQueryRequest request) {
        return !Boolean.FALSE.equals(request.getIncludeRowNumber());
    }

    private List<Header> stripRowNumberHeader(List<Header> headers) {
        if (headers == null || headers.isEmpty() || !isRowNumberHeader(headers.get(0))) {
            return headers == null ? Collections.emptyList() : headers;
        }
        return headers.subList(1, headers.size());
    }

    private List<List<String>> stripRowNumberRows(List<Header> headers, List<List<String>> rows) {
        if (rows == null || rows.isEmpty() || headers == null || headers.isEmpty() || !isRowNumberHeader(headers.get(0))) {
            return rows == null ? Collections.emptyList() : rows;
        }
        return rows.stream()
                .map(row -> row == null || row.isEmpty() ? Collections.<String>emptyList() : row.subList(1, row.size()))
                .toList();
    }

    private List<List<String>> cellRows2stringRows(List<List<ResultCell>> rows) {
        if (rows == null) {
            return Collections.emptyList();
        }
        return rows.stream()
                .filter(Objects::nonNull)
                .map(row -> row.stream().map(this::cell2string).toList())
                .toList();
    }

    private String cell2string(ResultCell cell) {
        return cell == null ? null : cell.getValue();
    }

    private boolean isRowNumberHeader(Header header) {
        if (header == null) {
            return false;
        }
        return DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode().equals(header.getDataType())
                || DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode().equals(header.getColumnType())
                || "CHAT2DB_ROW_NUMBER".equals(header.getDataType())
                || "CHAT2DB_ROW_NUMBER".equals(header.getColumnType())
                || isRowNumberHeaderName(header.getName())
                || isRowNumberHeaderName(header.getColumnName());
    }

    private boolean isRowNumberHeaderName(String value) {
        return StringUtils.isNotBlank(value)
                && (value.equals(I18nUtils.getMessage("sqlResult.rowNumber"))
                || value.equals(I18nUtils.getMessageByLang("sqlResult.rowNumber", Locale.US))
                || value.equals(I18nUtils.getMessageByLang("sqlResult.rowNumber", Locale.CHINA))
                || value.equals(I18nUtils.getMessageByLang("sqlResult.rowNumber", Locale.JAPAN)));
    }
}
