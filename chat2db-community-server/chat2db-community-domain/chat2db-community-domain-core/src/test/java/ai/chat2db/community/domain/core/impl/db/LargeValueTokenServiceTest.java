package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.LargeValueToken;
import ai.chat2db.community.domain.api.model.request.db.DbLargeValueTokensAttachRequest;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshot;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.model.LoginUser;
import ai.chat2db.community.tools.util.ContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeValueTokenServiceTest {

    @AfterEach
    void tearDown() {
        ContextUtils.removeContext();
    }

    @Test
    void bindsTokenToSnapshotPosition(@TempDir Path tempDirectory) {
        InMemoryResultSnapshotStore store = new InMemoryResultSnapshotStore(tempDirectory.resolve("snapshots"));
        DbLargeValueTokenServiceImpl service = new DbLargeValueTokenServiceImpl(
                InMemoryResultSnapshotStore.provider(store));
        setContext(7L, 9L);
        ResultCell cell = largeTextCell();
        ResultSnapshot snapshot = store.register();

        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), cell)),
                snapshot.getSnapshotId()));

        assertNotNull(cell.getLargeValueId());
        LargeValueToken token = service.requireValid(cell.getLargeValueId());
        assertEquals(snapshot.getSnapshotId(), token.getSnapshotId());
        assertEquals(0, token.getRowIndex());
        assertEquals(1, token.getColumnIndex());
        assertNotNull(token.getSnapshotId());
    }

    @Test
    void doesNotIssueTokenWithoutASnapshot() {
        DbLargeValueTokenServiceImpl service = new DbLargeValueTokenServiceImpl();
        setContext(7L, 9L);
        ResultCell cell = largeTextCell();

        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), cell)),
                null));

        assertTrue(cell.getLargeValueId() == null || cell.getLargeValueId().isEmpty());
        assertNotNull(cell.getUnsupportedReason());
    }

    @Test
    void rejectsExpiredToken(@TempDir Path tempDirectory) throws Exception {
        InMemoryResultSnapshotStore store = new InMemoryResultSnapshotStore(tempDirectory.resolve("snapshots"));
        DbLargeValueTokenServiceImpl service = new DbLargeValueTokenServiceImpl(
                InMemoryResultSnapshotStore.provider(store));
        setContext(7L, 9L);
        ResultCell cell = largeTextCell();
        ResultSnapshot snapshot = store.register();
        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), cell)),
                snapshot.getSnapshotId()));
        expire(service, cell.getLargeValueId());

        assertThrows(BusinessException.class, () -> service.requireValid(cell.getLargeValueId()));
    }



    @SuppressWarnings("unchecked")
    private static void expire(DbLargeValueTokenServiceImpl service, String tokenId) throws Exception {
        Field field = DbLargeValueTokenServiceImpl.class.getDeclaredField("tokenCache");
        field.setAccessible(true);
        Map<String, LargeValueToken> tokenCache = (Map<String, LargeValueToken>) field.get(service);
        tokenCache.get(tokenId).setExpiresAt(Instant.now().minusSeconds(1));
    }

    private static void setContext(Long userId, Long organizationId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        ContextUtils.setContext(Context.builder()
                .loginUser(loginUser)
                .organizationId(organizationId)
                .build());
    }

    private static Header rowNumberHeader() {
        return Header.builder().name("row").build();
    }

    @Test
    void releasesTheSnapshotOnceItsLastHandleExpires(@TempDir Path tempDirectory) {
        InMemoryResultSnapshotStore store = new InMemoryResultSnapshotStore(tempDirectory.resolve("snapshots"));
        DbLargeValueTokenServiceImpl service = new DbLargeValueTokenServiceImpl(
                InMemoryResultSnapshotStore.provider(store));
        ResultCell cell = largeTextCell();
        ResultSnapshot snapshot = store.register();
        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), cell)),
                snapshot.getSnapshotId()));
        assertTrue(store.exists(snapshot.getSnapshotId()));

        // Age the only handle past its lifetime, then let the next attach run the cleanup
        service.requireValid(cell.getLargeValueId()).setExpiresAt(Instant.now().minusSeconds(1));
        ResultCell next = largeTextCell();
        ResultSnapshot other = store.register();
        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), next)),
                other.getSnapshotId()));

        assertFalse(store.exists(snapshot.getSnapshotId()),
                "a snapshot whose last handle expired must be released, not kept for the whole snapshot TTL");
        assertTrue(store.exists(other.getSnapshotId()));
    }

    @Test
    void keepsTheSnapshotWhileAnotherHandleStillPointsAtIt(@TempDir Path tempDirectory) {
        InMemoryResultSnapshotStore store = new InMemoryResultSnapshotStore(tempDirectory.resolve("snapshots"));
        DbLargeValueTokenServiceImpl service = new DbLargeValueTokenServiceImpl(
                InMemoryResultSnapshotStore.provider(store));
        ResultCell first = largeTextCell();
        ResultCell second = largeTextCell();
        ResultSnapshot snapshot = store.register();
        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), first),
                        List.of(ResultCell.builder().value("2").build(), second)),
                snapshot.getSnapshotId()));

        service.requireValid(first.getLargeValueId()).setExpiresAt(Instant.now().minusSeconds(1));
        ResultCell next = largeTextCell();
        ResultSnapshot other = store.register();
        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), next)),
                other.getSnapshotId()));

        assertTrue(store.exists(snapshot.getSnapshotId()),
                "the snapshot must survive while one of its handles is still valid");
    }

    @Test
    void releasingHandlesFreesTheSnapshotImmediately(@TempDir Path tempDirectory) {
        InMemoryResultSnapshotStore store = new InMemoryResultSnapshotStore(tempDirectory.resolve("snapshots"));
        DbLargeValueTokenServiceImpl service = new DbLargeValueTokenServiceImpl(
                InMemoryResultSnapshotStore.provider(store));
        ResultCell cell = largeTextCell();
        ResultSnapshot snapshot = store.register();
        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), cell)),
                snapshot.getSnapshotId()));
        String handle = cell.getLargeValueId();
        assertTrue(store.exists(snapshot.getSnapshotId()));

        service.releaseTokens(List.of(handle));

        assertFalse(store.exists(snapshot.getSnapshotId()), "an explicit release must free the snapshot at once");
        assertThrows(BusinessException.class, () -> service.requireValid(handle));
    }

    @Test
    void releasingOneHandleKeepsASnapshotAnotherHandleStillUses(@TempDir Path tempDirectory) {
        InMemoryResultSnapshotStore store = new InMemoryResultSnapshotStore(tempDirectory.resolve("snapshots"));
        DbLargeValueTokenServiceImpl service = new DbLargeValueTokenServiceImpl(
                InMemoryResultSnapshotStore.provider(store));
        ResultCell first = largeTextCell();
        ResultCell second = largeTextCell();
        ResultSnapshot snapshot = store.register();
        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), first),
                        List.of(ResultCell.builder().value("2").build(), second)),
                snapshot.getSnapshotId()));

        service.releaseTokens(List.of(first.getLargeValueId()));

        assertTrue(store.exists(snapshot.getSnapshotId()),
                "the snapshot must survive while another handle still uses it");
        assertNotNull(service.requireValid(second.getLargeValueId()));

        service.releaseTokens(List.of(second.getLargeValueId(), "unknown-handle"));

        assertFalse(store.exists(snapshot.getSnapshotId()));
    }

    @Test
    void oneUncapturableCellOnlyDegradesThatCell(@TempDir Path tempDirectory) {
        InMemoryResultSnapshotStore store = new InMemoryResultSnapshotStore(tempDirectory.resolve("snapshots"));
        DbLargeValueTokenServiceImpl service = new DbLargeValueTokenServiceImpl(
                InMemoryResultSnapshotStore.provider(store));
        ResultCell good = largeTextCell();
        ResultCell bad = largeTextCell();
        ResultSnapshot snapshot = store.register();
        store.failCaptureAt(0, 2);

        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), good, bad)),
                snapshot.getSnapshotId()));

        assertNotNull(good.getLargeValueId(), "a healthy cell in the same row must still get a handle");
        assertNull(bad.getLargeValueId(), "the uncapturable cell must not get a handle");
        assertNotNull(bad.getUnsupportedReason(), "the uncapturable cell must be marked instead of failing the query");
    }

    @Test
    void lookingUpAnExpiredHandleReleasesItsContent(@TempDir Path tempDirectory) {
        InMemoryResultSnapshotStore store = new InMemoryResultSnapshotStore(tempDirectory.resolve("snapshots"));
        DbLargeValueTokenServiceImpl service = new DbLargeValueTokenServiceImpl(
                InMemoryResultSnapshotStore.provider(store));
        ResultCell cell = largeTextCell();
        ResultSnapshot snapshot = store.register();
        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), cell)),
                snapshot.getSnapshotId()));
        String handle = cell.getLargeValueId();
        service.requireValid(handle).setExpiresAt(Instant.now().minusSeconds(1));

        assertThrows(BusinessException.class, () -> service.requireValid(handle));

        assertFalse(store.exists(snapshot.getSnapshotId()),
                "an expired lookup must free the content instead of leaving it for the snapshot TTL");
    }

    @Test
    void lookingUpAnExpiredHandleKeepsASnapshotAnotherHandleUses(@TempDir Path tempDirectory) {
        InMemoryResultSnapshotStore store = new InMemoryResultSnapshotStore(tempDirectory.resolve("snapshots"));
        DbLargeValueTokenServiceImpl service = new DbLargeValueTokenServiceImpl(
                InMemoryResultSnapshotStore.provider(store));
        ResultCell first = largeTextCell();
        ResultCell second = largeTextCell();
        ResultSnapshot snapshot = store.register();
        service.attachTokens(attachLargeValueTokensRequest("doc",
                List.of(rowNumberHeader(), valueHeader()),
                List.of(List.of(ResultCell.builder().value("1").build(), first),
                        List.of(ResultCell.builder().value("2").build(), second)),
                snapshot.getSnapshotId()));
        service.requireValid(first.getLargeValueId()).setExpiresAt(Instant.now().minusSeconds(1));

        assertThrows(BusinessException.class, () -> service.requireValid(first.getLargeValueId()));

        assertTrue(store.exists(snapshot.getSnapshotId()),
                "the other handle still needs the snapshot");
        assertNotNull(service.requireValid(second.getLargeValueId()));
    }

    private static DbLargeValueTokensAttachRequest attachLargeValueTokensRequest(String tableName,
                                                                               List<Header> headers,
                                                                               List<List<ResultCell>> dataList,
                                                                               String snapshotId) {
        DbLargeValueTokensAttachRequest request = new DbLargeValueTokensAttachRequest();
        request.setTableName(tableName);
        request.setHeaders(headers);
        request.setDataList(dataList);
        request.setSnapshotId(snapshotId);
        return request;
    }

    private static Header valueHeader() {
        return Header.builder().name("content").columnName("content").columnType("LONGTEXT").build();
    }

    private static ResultCell largeTextCell() {
        return ResultCell.builder()
                .value("[LONGTEXT] 20.00 MB")
                // The snapshot captures the materialized content, so the cell must carry it
                .rawValue("complete content")
                .largeValue(true)
                .truncated(true)
                .valueType("TEXT")
                .columnType("LONGTEXT")
                .sizeBytes(20L * 1024L * 1024L)
                .build();
    }
}
