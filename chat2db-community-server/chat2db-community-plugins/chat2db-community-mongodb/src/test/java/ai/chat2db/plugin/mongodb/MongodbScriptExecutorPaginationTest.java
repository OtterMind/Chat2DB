package ai.chat2db.plugin.mongodb;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.tools.constant.IEasyToolsConstant;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongodbScriptExecutorPaginationTest {

    private static Field messageSourceField;
    private static MessageSource previousMessageSource;

    @BeforeAll
    static void setUpI18n() throws ReflectiveOperationException {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.setUseCodeAsDefaultMessage(true);
        messageSourceField = I18nUtils.class.getDeclaredField("messageSourceStatic");
        messageSourceField.setAccessible(true);
        previousMessageSource = (MessageSource) messageSourceField.get(null);
        messageSourceField.set(null, messageSource);
    }

    @AfterAll
    static void restoreI18n() throws IllegalAccessException {
        messageSourceField.set(null, previousMessageSource);
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.close();
    }

    @Test
    void calculatesLargeBoundsWithoutIntegerMultiplicationOrAdditionOverflow() {
        MongodbScriptExecutor.PageBounds multiplication =
            MongodbScriptExecutor.normalizePageBounds(50_000, 50_000);
        assertEquals(2_499_950_000L, multiplication.fromIndex());
        assertEquals(2_500_000_000L, multiplication.toIndex());

        MongodbScriptExecutor.PageBounds addition =
            MongodbScriptExecutor.normalizePageBounds(2, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, addition.fromIndex());
        assertEquals(2L * Integer.MAX_VALUE, addition.toIndex());

        MongodbScriptExecutor.PageBounds maximum =
            MongodbScriptExecutor.normalizePageBounds(Integer.MAX_VALUE, Integer.MAX_VALUE);
        long maximumOffset = ((long) Integer.MAX_VALUE - 1L) * Integer.MAX_VALUE;
        assertEquals(maximumOffset, maximum.fromIndex());
        assertEquals(maximumOffset + Integer.MAX_VALUE, maximum.toIndex());
    }

    @Test
    void defaultsInvalidBoundsToTheFirstPageAndDefaultSize() {
        assertBounds(null, null, 1, IEasyToolsConstant.DEFAULT_PAGE_SIZE, 0L);
        assertBounds(0, 0, 1, IEasyToolsConstant.DEFAULT_PAGE_SIZE, 0L);
        assertBounds(-1, -1, 1, IEasyToolsConstant.DEFAULT_PAGE_SIZE, 0L);
    }

    @Test
    void selectTableReturnsAnEmptyResultForAZeroRowResultSet() {
        QueryStub stub = setup(List.of());
        SqlExecuteRequest request = request(1, 10);
        request.setTableName("widgets");

        ExecuteResponse response = new MongodbScriptExecutor().executeSelectTable(request).get(0);

        assertTrue(response.getDataList().isEmpty());
        assertEquals("0", response.getFuzzyTotal());
        assertFalse(response.getHasNextPage());
        assertEquals(1, stub.nextCalls());
        assertEquals(0, stub.getObjectCalls());
    }

    @Test
    void freeFormQueryReturnsAnEmptyResultForAZeroRowResultSet() {
        setup(List.of());
        SqlExecuteRequest request = request(1, 10);
        request.setScript("db.widgets.find({missing: true})");

        ExecuteResponse response = new MongodbScriptExecutor().execute(request).get(0);

        assertTrue(response.getDataList().isEmpty());
        assertEquals("0", response.getFuzzyTotal());
        assertFalse(response.getHasNextPage());
    }

    @Test
    void pageSliceIsIndependentFromTheAccumulatedRows() {
        List<Integer> accumulatedRows = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        List<Integer> page = MongodbScriptExecutor.normalizePageBounds(2, 2).page(accumulatedRows);

        accumulatedRows.set(2, 99);
        accumulatedRows.clear();

        assertEquals(List.of(3, 4), page);
    }

    @Test
    void selectTableReturnsAnEmptyPageWhenMaximumBoundsExceedAvailableRows() {
        QueryStub stub = setup(List.of(document(1), document(2), document(3)));
        SqlExecuteRequest request = request(Integer.MAX_VALUE, Integer.MAX_VALUE);
        request.setTableName("widgets");

        ExecuteResponse response = new MongodbScriptExecutor().executeSelectTable(request).get(0);

        assertTrue(response.getDataList().isEmpty());
        assertEquals("3", response.getFuzzyTotal());
        assertEquals("db.getCollection(\"widgets\").find()", stub.executedSql());
    }

    @Test
    void freeFormQueryReturnsAnEmptyPageWhenEndIndexExceedsIntegerRange() {
        setup(List.of(document(1), document(2), document(3)));
        SqlExecuteRequest request = request(2, Integer.MAX_VALUE);
        request.setScript("db.widgets.find({})");

        ExecuteResponse response = new MongodbScriptExecutor().execute(request).get(0);

        assertTrue(response.getDataList().isEmpty());
        assertEquals("3", response.getFuzzyTotal());
    }

    @Test
    void normalPagesKeepRowsAndFuzzyTotalSemantics() {
        QueryStub fullPageStub = setup(
            List.of(document(1), document(2), document(3), document(4), document(5)), 7);
        SqlExecuteRequest fullPageRequest = request(2, 2);
        fullPageRequest.setScript("db.widgets.find({})");

        ExecuteResponse fullPage = new MongodbScriptExecutor().execute(fullPageRequest).get(0);

        assertEquals(List.of("3", "4"), rowNumbers(fullPage));
        assertEquals("2+", fullPage.getFuzzyTotal());
        assertTrue(fullPage.getHasNextPage());
        assertEquals(4, fullPageStub.nextCalls());
        assertEquals(4, fullPageStub.getObjectCalls());

        setup(List.of(document(1), document(2), document(3), document(4), document(5)));
        SqlExecuteRequest lastPageRequest = request(2, 3);
        lastPageRequest.setScript("db.widgets.find({})");

        ExecuteResponse lastPage = new MongodbScriptExecutor().execute(lastPageRequest).get(0);

        assertEquals(List.of("4", "5"), rowNumbers(lastPage));
        assertEquals("5", lastPage.getFuzzyTotal());
        assertFalse(lastPage.getHasNextPage());
    }

    @Test
    void scalarRowsKeepTheirExistingSingleResultRowShape() {
        setupScalars(List.of("alpha", "beta"));
        SqlExecuteRequest request = request(1, 10);
        request.setScript("db.runCommand({ping: 1})");

        ExecuteResponse response = new MongodbScriptExecutor().execute(request).get(0);

        assertEquals(1, response.getDataList().size());
        assertEquals(List.of("alpha", "beta"),
            response.getDataList().get(0).stream().map(cell -> cell.getValue()).toList());
        assertEquals("1", response.getFuzzyTotal());
    }

    private QueryStub setup(List<Map<String, Object>> documents) {
        return setup(documents, 1);
    }

    private QueryStub setup(List<Map<String, Object>> documents, int columnCount) {
        return setupRows(documents, columnCount);
    }

    private QueryStub setupScalars(List<String> values) {
        return setupRows(values.stream().map(ScalarRow::new).toList(), 1);
    }

    private QueryStub setupRows(List<?> rows, int columnCount) {
        Chat2DBContext.close();
        QueryStub stub = new QueryStub(rows, columnCount);
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("MONGODB");
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(stub.connection());
        Chat2DBContext.putContext(connectInfo);
        return stub;
    }

    private static SqlExecuteRequest request(int pageNo, int pageSize) {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setPageNo(pageNo);
        request.setPageSize(pageSize);
        return request;
    }

    private static void assertBounds(Integer requestedPageNo, Integer requestedPageSize,
                                     int pageNo, int pageSize, long fromIndex) {
        MongodbScriptExecutor.PageBounds bounds =
            MongodbScriptExecutor.normalizePageBounds(requestedPageNo, requestedPageSize);
        assertEquals(pageNo, bounds.pageNo());
        assertEquals(pageSize, bounds.pageSize());
        assertEquals(fromIndex, bounds.fromIndex());
    }

    private static Map<String, Object> document(int id) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("_id", id);
        return document;
    }

    private static List<String> rowNumbers(ExecuteResponse response) {
        return response.getDataList().stream().map(row -> row.get(0).getValue()).toList();
    }

    private record ScalarRow(String value) {
    }

    private static final class QueryStub {

        private final List<?> rows;
        private final int columnCount;
        private final List<String> executedSql = new ArrayList<>();
        private int nextCalls;
        private int getObjectCalls;

        private QueryStub(List<?> rows, int columnCount) {
            this.rows = rows;
            this.columnCount = columnCount;
        }

        private String executedSql() {
            return executedSql.get(0);
        }

        private int nextCalls() {
            return nextCalls;
        }

        private int getObjectCalls() {
            return getObjectCalls;
        }

        private Connection connection() {
            return proxy(Connection.class, (target, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> statement((String) args[0]);
                case "isClosed" -> false;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement statement(String sql) {
            ResultSet resultSet = resultSet();
            return proxy(PreparedStatement.class, (target, method, args) -> switch (method.getName()) {
                case "execute" -> {
                    executedSql.add(sql);
                    yield true;
                }
                case "getResultSet" -> resultSet;
                case "setFetchSize", "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet() {
            int[] cursor = {-1};
            ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (target, method, args) ->
                switch (method.getName()) {
                    case "getColumnCount" -> columnCount;
                    case "getColumnName" -> "document";
                    default -> defaultValue(method.getReturnType());
                });
            return proxy(ResultSet.class, (target, method, args) -> switch (method.getName()) {
                case "next" -> {
                    nextCalls++;
                    yield ++cursor[0] < rows.size();
                }
                case "getObject" -> {
                    getObjectCalls++;
                    Object row = rows.get(cursor[0]);
                    yield row instanceof ScalarRow ? null : row;
                }
                case "getString" -> rows.get(cursor[0]) instanceof ScalarRow row ? row.value() : null;
                case "getMetaData" -> metadata;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
