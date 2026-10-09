package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.db.CellValueChunk;
import ai.chat2db.community.domain.api.model.db.LargeValueReference;
import ai.chat2db.community.domain.api.model.db.LargeValueToken;
import ai.chat2db.community.domain.api.model.request.db.DbCellValueTokenReadRequest;
import ai.chat2db.community.web.api.model.request.db.cell.CellValueReadRequest;
import ai.chat2db.community.web.api.model.response.db.cell.CellValueChunkResponse;
import org.springframework.stereotype.Component;

@Component
public class CellValueConverter {

    public DbCellValueTokenReadRequest readRequest2param(CellValueReadRequest request) {
        if (request == null) {
            return null;
        }
        DbCellValueTokenReadRequest param = new DbCellValueTokenReadRequest();
        param.setLargeValueId(request.getLargeValueId());
        param.setOffset(request.getOffset());
        param.setLimit(request.getLimit());
        param.setFormat(request.getFormat());
        return param;
    }

    public LargeValueReference token2reference(LargeValueToken token) {
        if (token == null) {
            return null;
        }
        return LargeValueReference.builder()
                .tableName(token.getTableName())
                .columnName(token.getColumnName())
                .valueType(token.getValueType())
                .sqlType(token.getSqlType())
                .columnType(token.getColumnType())
                .snapshotId(token.getSnapshotId())
                .rowIndex(token.getRowIndex())
                .columnIndex(token.getColumnIndex())
                .build();
    }

    public CellValueChunkResponse chunk2response(CellValueChunk chunk) {
        if (chunk == null) {
            return null;
        }
        return CellValueChunkResponse.builder()
                .value(chunk.getValue())
                .offset(chunk.getOffset())
                .nextOffset(chunk.getNextOffset())
                .eof(chunk.isEof())
                .sizeBytes(chunk.getSizeBytes())
                .sizeChars(chunk.getSizeChars())
                .encoding(chunk.getEncoding())
                .contentType(chunk.getContentType())
                .displayMode(chunk.getDisplayMode())
                .build();
    }
}
