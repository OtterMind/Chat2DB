package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.LargeValueToken;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshotCell;
import ai.chat2db.community.domain.api.service.result.IResultSnapshotStore;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.request.db.DbLargeValueTokensAttachRequest;
import ai.chat2db.community.domain.api.service.db.IDbLargeValueTokenService;
import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DbLargeValueTokenServiceImpl implements IDbLargeValueTokenService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    /**
     * Upper bound for a single captured value. The snapshot itself is capped on disk as well, but a value has to be
     * materialized and encoded before that check can run, so an absurdly large single cell is not captured at all.
     */
    private static final long MAX_CAPTURED_CELL_BYTES = 256L * 1024 * 1024;

    private static final String UNSUPPORTED_REASON_KEY = "largeCellValue.fullValueUnsupported";

    private final Map<String, LargeValueToken> tokenCache = new ConcurrentHashMap<>();

    private final ObjectProvider<IResultSnapshotStore> snapshotStoreProvider;

    public DbLargeValueTokenServiceImpl() {
        this(null);
    }

    @Autowired
    public DbLargeValueTokenServiceImpl(ObjectProvider<IResultSnapshotStore> snapshotStoreProvider) {
        this.snapshotStoreProvider = snapshotStoreProvider;
    }

    private IResultSnapshotStore snapshotStore() {
        return snapshotStoreProvider == null ? null : snapshotStoreProvider.getIfAvailable();
    }

    @Override
    public void attachTokens(DbLargeValueTokensAttachRequest attachLargeValueTokensRequest) {
        String tableName = attachLargeValueTokensRequest == null ? null : attachLargeValueTokensRequest.getTableName();
        List<Header> headers = attachLargeValueTokensRequest == null ? null : attachLargeValueTokensRequest.getHeaders();
        List<List<ResultCell>> dataList = attachLargeValueTokensRequest == null ? null : attachLargeValueTokensRequest.getDataList();
        if (CollectionUtils.isEmpty(headers) || CollectionUtils.isEmpty(dataList)) {
            return;
        }
        cleanupExpired();
        String snapshotId = attachLargeValueTokensRequest.getSnapshotId();
        IResultSnapshotStore snapshotStore = snapshotId == null ? null : snapshotStore();
        if (snapshotStore != null && snapshotId != null) {
            attachSnapshotTokens(snapshotStore, attachLargeValueTokensRequest, snapshotId, headers, dataList);
            return;
        }
        // Without a snapshot there is no content to serve, so every large value stays unresolved.
        for (List<ResultCell> row : dataList) {
            if (row == null) {
                continue;
            }
            for (ResultCell cell : row) {
                if (cell != null && cell.isLargeValue()) {
                    cell.setUnsupportedReason(UNSUPPORTED_REASON_KEY);
                }
            }
        }
    }

    /**
     * Captures the complete content of every large value into the result snapshot while the result set is still open,
     * so the read path never has to re-query the row.
     */
    private void attachSnapshotTokens(IResultSnapshotStore snapshotStore, DbLargeValueTokensAttachRequest attachRequest,
                                      String snapshotId, List<Header> headers, List<List<ResultCell>> dataList) {
        int rowOffset = attachRequest.getRowOffset();
        for (int rowIndex = 0; rowIndex < dataList.size(); rowIndex++) {
            List<ResultCell> row = dataList.get(rowIndex);
            if (row == null) {
                continue;
            }
            int absoluteRow = rowOffset + rowIndex;
            for (int colIndex = 0; colIndex < row.size() && colIndex < headers.size(); colIndex++) {
                ResultCell cell = row.get(colIndex);
                if (cell == null || !cell.isLargeValue()) {
                    continue;
                }
                if (cell.getRawValue() == null) {
                    // The value was never materialized, so there is nothing to capture. Keeping the cell unresolved is
                    // safer than capturing the truncated preview as if it were the complete value.
                    cell.setUnsupportedReason(UNSUPPORTED_REASON_KEY);
                    continue;
                }
                if (cell.getSizeBytes() != null && cell.getSizeBytes() > MAX_CAPTURED_CELL_BYTES) {
                    // Copying a value this large into the snapshot would need several times its size on the heap before
                    // any limit applies, so the cell is reported as not captured instead of risking the process.
                    log.warn("Skipping capture of a {} byte large value at row {} column {}: above the per cell limit",
                            cell.getSizeBytes(), absoluteRow, colIndex);
                    cell.setUnsupportedReason(UNSUPPORTED_REASON_KEY);
                    continue;
                }
                int absoluteColumn = colIndex;
                ResultSnapshotCell captured;
                try {
                    captured = snapshotStore.capture(snapshotId, absoluteRow, absoluteColumn, cell.getRawValue());
                } catch (RuntimeException e) {
                    // One uncapturable value must not fail the whole statement: mark that cell and keep going.
                    log.warn("Failed to capture a large value at row {} column {}: {}", absoluteRow, absoluteColumn,
                            e.getMessage());
                    cell.setUnsupportedReason(UNSUPPORTED_REASON_KEY);
                    continue;
                }
                // The snapshot reports the real byte and character counts, which the driver metadata may not match
                cell.setSizeBytes(captured.getSizeBytes());
                cell.setSizeChars(captured.getSizeChars());
                LargeValueToken token = createToken(attachRequest.getTableName(), columnName(headers, colIndex), cell,
                        snapshotId, absoluteRow, absoluteColumn);
                tokenCache.put(token.getId(), token);
                cell.setLargeValueId(token.getId());
                // The snapshot owns the full content now, so the response no longer has to keep a second copy.
                cell.setRawValue(null);
            }
        }
    }

    @Override
    public LargeValueToken requireValid(String id) {
        if (StringUtils.isBlank(id)) {
            throw new BusinessException("largeCellValue.tokenRequired");
        }
        LargeValueToken token = tokenCache.get(id);
        if (token == null) {
            throw new BusinessException("largeCellValue.tokenExpired");
        }
        if (Instant.now().isAfter(token.getExpiresAt())) {
            tokenCache.remove(id);
            // The lookup is often the only signal that a handle is gone, so free its content right here instead of
            // waiting for the next attach or the snapshot TTL.
            if (token.getSnapshotId() != null) {
                releaseUnreferenced(Set.of(token.getSnapshotId()));
            }
            throw new BusinessException("largeCellValue.tokenExpired");
        }
        return token;
    }

    /**
     * Releases the content of handles that ran out of time even when no further query is executed, so an idle
     * application does not hold captured values until the snapshot TTL.
     */
    @Scheduled(fixedDelay = 60_000L)
    public void evictExpiredTokens() {
        cleanupExpired();
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        Set<String> releasedCandidates = new HashSet<>();
        tokenCache.entrySet().removeIf(entry -> {
            if (!now.isAfter(entry.getValue().getExpiresAt())) {
                return false;
            }
            if (entry.getValue().getSnapshotId() != null) {
                releasedCandidates.add(entry.getValue().getSnapshotId());
            }
            return true;
        });
        releaseUnreferenced(releasedCandidates);
    }

    @Override
    public void releaseUnusedFor(String snapshotId) {
        if (StringUtils.isBlank(snapshotId)) {
            return;
        }
        boolean stillReferenced = tokenCache.values().stream()
                .anyMatch(token -> snapshotId.equals(token.getSnapshotId()));
        if (stillReferenced) {
            return;
        }
        IResultSnapshotStore store = snapshotStore();
        if (store != null && store.isIdle(snapshotId)) {
            store.release(snapshotId);
        }
    }

    @Override
    public void releaseTokens(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<String> candidates = new HashSet<>();
        for (String id : ids) {
            if (StringUtils.isBlank(id)) {
                continue;
            }
            LargeValueToken removed = tokenCache.remove(id);
            if (removed != null && removed.getSnapshotId() != null) {
                candidates.add(removed.getSnapshotId());
            }
        }
        releaseUnreferenced(candidates);
    }

    /**
     * Frees every snapshot in {@code candidates} that no remaining handle points at, so the last handle going away
     * releases the captured content instead of leaving it for the whole snapshot TTL.
     */
    private void releaseUnreferenced(Set<String> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        // The caller may hand in an immutable set, so the filtering works on its own copy
        Set<String> releasable = new HashSet<>(candidates);
        Set<String> stillReferenced = new HashSet<>();
        tokenCache.values().forEach(token -> {
            if (token.getSnapshotId() != null) {
                stillReferenced.add(token.getSnapshotId());
            }
        });
        releasable.removeAll(stillReferenced);
        if (releasable.isEmpty()) {
            return;
        }
        IResultSnapshotStore store = snapshotStore();
        if (store == null) {
            return;
        }
        // A snapshot that is still being captured (or is being downloaded) must survive even when no handle points at
        // it right now, otherwise a release from the client kills the rest of a running query.
        releasable.stream().filter(store::isIdle).forEach(store::release);
    }


    private String columnName(List<Header> headers, int colIndex) {
        Header header = colIndex < headers.size() ? headers.get(colIndex) : null;
        return header == null ? null : StringUtils.defaultIfBlank(header.getColumnName(), header.getName());
    }

    private LargeValueToken createToken(String tableName, String columnName, ResultCell cell, String snapshotId,
                                        Integer rowIndex, Integer columnIndex) {
        return LargeValueToken.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .tableName(tableName)
                .columnName(columnName)
                .expiresAt(Instant.now().plus(DEFAULT_TTL))
                .valueType(cell.getValueType())
                .sqlType(cell.getSqlType())
                .columnType(cell.getColumnType())
                .snapshotId(snapshotId)
                .rowIndex(rowIndex)
                .columnIndex(columnIndex)
                .build();
    }


}
