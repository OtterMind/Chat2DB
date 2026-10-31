package ai.chat2db.community.web.api.adapter.db.execution;

import ai.chat2db.community.domain.api.model.db.LargeValueToken;
import ai.chat2db.community.domain.api.model.request.db.DbExecuteResultEnhanceRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotReadChunk;
import ai.chat2db.community.domain.api.service.db.IDbExecuteResultEnhanceService;
import ai.chat2db.community.domain.api.service.result.IResultSnapshotStore;
import ai.chat2db.community.domain.core.impl.db.DbLargeValueTokenServiceImpl;
import ai.chat2db.community.storage.snapshot.FileResultSnapshotStore;
import ai.chat2db.community.web.api.converter.db.DbWebConverterImpl;
import ai.chat2db.community.web.api.model.request.db.SqlEditorExecuteRequest;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.ResultCursorLeaseRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the leased read path end to end at the wiring level: a value the driver handed over as a handle is kept on
 * its cursor by the streaming consumer, read from there when the client asks for it, and the cursor and its connection
 * are given back once the last handle is released.
 */
class SqlExecutionConsumerLeaseTest {

    @Test
    void aValueHandedOverAsAHandleIsReadFromItsCursorOnlyWhenItIsAskedFor(@TempDir Path tempDirectory) throws Exception {
        Path root = tempDirectory.resolve("result-snapshot");
        FileResultSnapshotStore store = new FileResultSnapshotStore(root);
        store.prepareRootDirectory();
        DbLargeValueTokenServiceImpl tokenService = new DbLargeValueTokenServiceImpl(provider(store));
        SqlExecutionConsumer consumer = new SqlExecutionConsumer(request(), new CapturingSink(), new DbWebConverterImpl(),
                tokenService, noopEnhancer(), new SqlExecutionEventContext(), store);

        String text = "租约文本-".repeat(200_000);
        LazyClob clob = new LazyClob(text);
        AtomicBoolean statementClosed = new AtomicBoolean();
        AtomicBoolean connectionClosed = new AtomicBoolean();
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConnection(connection(connectionClosed));

        ResultCell cell = ResultCell.builder()
                .value("租约…")
                .rawValue(clob)
                .largeValue(true)
                .truncated(true)
                .valueType("TEXT")
                .columnType("CLOB")
                .sizeBytes((long) text.getBytes(StandardCharsets.UTF_8).length)
                .build();
        List<List<ResultCell>> rows = List.of(List.of(ResultCell.of("1"), cell));
        ExecuteResponse result = ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .resultSetId(1)
                .headerList(List.of(Header.builder().name("row").build(),
                        Header.builder().name("payload").columnName("payload").build()))
                .dataList(rows)
                .build();

        ResultCursorLeaseRegistry.begin(connectInfo, statement(statementClosed));
        try {
            consumer.rows(result, rows);
        } finally {
            ResultCursorLeaseRegistry.end();
        }

        assertNotNull(cell.getLargeValueId(), "the client must receive a readable id");
        assertEquals(0, clob.reads(), "leasing a value must not read it");
        assertFalse(statementClosed.get(), "the cursor stays open while the snapshot leases a value");
        try (var entries = Files.list(root)) {
            assertEquals(0L, entries.filter(Files::isDirectory).count(), "a leased value is not written to disk");
        }

        LargeValueToken token = tokenService.requireValid(cell.getLargeValueId());
        String assembled = readAll(store, token);

        assertEquals(text, assembled, "the value must be served from the cursor");
        assertTrue(clob.reads() > 0, "the cursor is really read");

        consumer.close();
        tokenService.releaseTokens(List.of(token.getId()));

        assertTrue(statementClosed.get(), "releasing the last handle closes the cursor");
        assertTrue(connectionClosed.get(), "and gives the connection back");
    }

    @Test
    void aValueBelowTheLeaseThresholdIsCapturedAsBefore(@TempDir Path tempDirectory) {
        Path root = tempDirectory.resolve("result-snapshot");
        FileResultSnapshotStore store = new FileResultSnapshotStore(root);
        store.prepareRootDirectory();
        DbLargeValueTokenServiceImpl tokenService = new DbLargeValueTokenServiceImpl(provider(store));
        SqlExecutionConsumer consumer = new SqlExecutionConsumer(request(), new CapturingSink(), new DbWebConverterImpl(),
                tokenService, noopEnhancer(), new SqlExecutionEventContext(), store);

        String text = "小值-".repeat(5_000);
        LazyClob clob = new LazyClob(text);
        AtomicBoolean statementClosed = new AtomicBoolean();
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConnection(connection(new AtomicBoolean()));
        ResultCell cell = ResultCell.builder()
                .value("小值…")
                .rawValue(clob)
                .largeValue(true)
                .truncated(true)
                .valueType("TEXT")
                .columnType("CLOB")
                .sizeBytes((long) text.getBytes(StandardCharsets.UTF_8).length)
                .build();
        List<List<ResultCell>> rows = List.of(List.of(ResultCell.of("1"), cell));
        ExecuteResponse result = ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .resultSetId(1)
                .headerList(List.of(Header.builder().name("row").build(),
                        Header.builder().name("payload").columnName("payload").build()))
                .dataList(rows)
                .build();

        ResultCursorLeaseRegistry.begin(connectInfo, statement(statementClosed));
        try {
            consumer.rows(result, rows);
        } finally {
            ResultCursorLeaseRegistry.end();
        }

        LargeValueToken token = tokenService.requireValid(cell.getLargeValueId());
        assertEquals(text, readAll(store, token), "a small value is copied into the snapshot");
        assertTrue(clob.reads() > 0, "capturing reads the handle");
        assertFalse(statementClosed.get(), "nothing was leased, so the executor keeps its own cursor");
    }

    private static String readAll(IResultSnapshotStore store, LargeValueToken token) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long offset = 0L;
        int guard = 0;
        while (true) {
            SnapshotReadChunk chunk = store.read(token.getSnapshotId(), token.getRowIndex(), token.getColumnIndex(),
                    offset, 256 * 1024);
            byte[] slice = Base64.getDecoder().decode(chunk.getValue());
            bytes.write(slice, 0, slice.length);
            offset = chunk.getNextOffset();
            if (chunk.isEof()) {
                break;
            }
            assertTrue(++guard < 2000, "chunk loop must terminate");
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static SqlExecutionRequest request() {
        return SqlExecutionRequest.builder()
                .requestUuid("lease-test")
                .sqlEditorRequest(new SqlEditorExecuteRequest())
                .build();
    }

    private static IDbExecuteResultEnhanceService noopEnhancer() {
        return new IDbExecuteResultEnhanceService() {
            @Override
            public void enhance(DbExecuteResultEnhanceRequest dbExecuteResultEnhanceRequest) {
            }
        };
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private static Statement statement(AtomicBoolean closed) {
        return (Statement) Proxy.newProxyInstance(
                SqlExecutionConsumerLeaseTest.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "close":
                            closed.set(true);
                            return null;
                        case "isClosed":
                            return closed.get();
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Connection connection(AtomicBoolean closed) {
        return (Connection) Proxy.newProxyInstance(
                SqlExecutionConsumerLeaseTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "close":
                            closed.set(true);
                            return null;
                        case "isClosed":
                            return closed.get();
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }

    /**
     * A Clob that only produces its content when it is read, and counts how often that happened.
     */
    private static final class LazyClob implements Clob {

        private final String value;
        private int reads;

        private LazyClob(String value) {
            this.value = value;
        }

        private int reads() {
            return reads;
        }

        @Override
        public long length() {
            return value.length();
        }

        @Override
        public String getSubString(long pos, int length) {
            return value.substring((int) pos - 1, (int) pos - 1 + length);
        }

        @Override
        public Reader getCharacterStream() {
            reads++;
            return new StringReader(value);
        }

        @Override
        public InputStream getAsciiStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position(String searchstr, long start) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position(Clob searchstr, long start) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int setString(long pos, String str) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int setString(long pos, String str, int offset, int len) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream setAsciiStream(long pos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Writer setCharacterStream(long pos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void truncate(long len) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void free() {
        }

        @Override
        public Reader getCharacterStream(long pos, long length) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CapturingSink implements ISqlExecutionSink {

        private final List<String> events = new ArrayList<>();

        @Override
        public void send(String eventType, Object message, Integer statementSequence, Integer resultSequence) {
            events.add(eventType);
        }
    }
}
