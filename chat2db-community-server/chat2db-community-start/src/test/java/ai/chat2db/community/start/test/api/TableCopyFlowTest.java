package ai.chat2db.community.start.test.api;

import ai.chat2db.community.domain.api.model.request.db.DbTableCopyRequest;
import ai.chat2db.community.domain.core.impl.db.DbTableServiceImpl;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.web.api.config.console.ConsoleHelper;
import ai.chat2db.community.web.api.config.console.RequestMappingInfo;
import ai.chat2db.community.web.api.util.ApplicationContextUtil;
import ai.chat2db.community.web.api.util.RequestMappingUtils;
import ai.chat2db.plugin.clickhouse.ClickHouseDBManager;
import ai.chat2db.plugin.dm.DMDBManager;
import ai.chat2db.plugin.mysql.MysqlDBManager;
import ai.chat2db.plugin.mysql.MysqlMetaData;
import ai.chat2db.plugin.oracle.OracleDBManager;
import ai.chat2db.plugin.postgresql.PostgreSQLDBManager;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TableCopyFlowTest {
    private static final String LONG_TABLE = "inventory_material_record_source12345678901";
    private final DbTableServiceImpl service = new DbTableServiceImpl(null);

    @Test
    void preparationOnlyReturnsANameAndNeverTouchesTheDatabase() {
        try (MockedStatic<Chat2DBContext> context = mockStatic(Chat2DBContext.class)) {
            String name = service.prepareCopyTable(LONG_TABLE);
            assertEquals(32, name.length());
            assertTrue(name.matches("inventory_materi_copy_[0-9]{10}"));
            context.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mysqlCopiesDataAndStructureThroughTheService(boolean copyData) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:copy_flow;MODE=MySQL")) {
            connection.createStatement().execute("CREATE TABLE `" + LONG_TABLE + "` (id BIGINT PRIMARY KEY, rev_no VARCHAR(200))");
            connection.createStatement().execute("INSERT INTO `" + LONG_TABLE + "` VALUES (1, 'A'), (2, NULL)");
            try (MockedStatic<Chat2DBContext> context = context(connection, new MysqlDBManager(), new MysqlMetaData())) {
                service.copyTable(request(LONG_TABLE, "material_copy", copyData));
            }
            try (ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM `material_copy`")) {
                assertTrue(rows.next());
                assertEquals(copyData ? 2 : 0, rows.getInt(1));
            }
            if (copyData) {
                try (ResultSet rows = connection.createStatement().executeQuery("SELECT rev_no FROM `material_copy` ORDER BY id")) {
                    assertTrue(rows.next());
                    assertEquals("A", rows.getString(1));
                    assertTrue(rows.next());
                    assertNull(rows.getString(1));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders", "select", "order details", "ord`ers", "`orders`", "a`; DROP TABLE b; --"})
    void mysqlQuotesRawNamesExactlyOnce(String name) throws Exception {
        Connection connection = recordingConnection();
        try (MockedStatic<Chat2DBContext> context = context(connection, new MysqlDBManager(), new MysqlMetaData())) {
            service.copyTable(request(name, "new`table", true));
        }
        verify(connection).prepareStatement("CREATE TABLE `new``table` AS SELECT * FROM `"
                + name.replace("`", "``") + "`");
    }

    @Test
    void generatedNameKeepsTheCopySuffixForLongTableNames() throws Exception {
        Connection connection = recordingConnection();
        IDbManager manager = mock(IDbManager.class);
        try (MockedStatic<Chat2DBContext> context = context(connection, manager, new MysqlMetaData())) {
            service.copyTable(request(LONG_TABLE, null, true));
        }
        var target = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(manager).copyTable(eq(connection), eq("app"), isNull(), eq(LONG_TABLE), target.capture(), eq(true));
        assertEquals(32, target.getValue().length());
        assertTrue(target.getValue().matches("inventory_materi_copy_[0-9]{10}"), target.getValue());
    }

    @Test
    void defaultManagerStillQuotesNamesForPluginsWithoutAnOverride() throws Exception {
        Connection connection = recordingConnection();
        IDbMetaData metadata = new DefaultMetaService() {
            @Override
            public String getMetaDataName(String... names) {
                return Arrays.stream(names).map(name -> "\"" + name.replace("\"", "\"\"") + "\"")
                        .collect(Collectors.joining("."));
            }
        };
        try (MockedStatic<Chat2DBContext> context = context(connection, new DefaultDBManager(), metadata)) {
            service.copyTable(request("order details", "ord\"ers", false));
        }
        verify(connection).prepareStatement("CREATE TABLE \"ord\"\"ers\" AS SELECT * FROM \"order details\" WHERE 1=0");
    }

    @Test
    void otherDialectCopyManagersPreserveLiteralQuoteCharacters() throws Exception {
        for (IDbManager manager : List.of(new PostgreSQLDBManager(), new OracleDBManager(), new DMDBManager())) {
            Connection connection = recordingConnection();
            try (MockedStatic<Chat2DBContext> context = context(connection, manager, new DefaultMetaService())) {
                service.copyTable(request("\"orders\"", "\"copy\"", true));
            }
            String source = "\"\"\"orders\"\"\"";
            String target = "\"\"\"copy\"\"\"";
            String expected = manager instanceof PostgreSQLDBManager
                    ? "CREATE TABLE " + target + " AS TABLE " + source + " WITH DATA"
                    : "CREATE TABLE " + target + " AS SELECT * FROM " + source;
            verify(connection).prepareStatement(expected);
        }
        Connection connection = recordingConnection();
        try (MockedStatic<Chat2DBContext> context = context(connection, new ClickHouseDBManager(), new DefaultMetaService())) {
            service.copyTable(request("`orders`", "`copy`", true));
        }
        verify(connection).prepareStatement("CREATE TABLE `app`.```copy``` AS `app`.```orders```");
        verify(connection).prepareStatement("INSERT INTO `app`.```copy``` SELECT * FROM `app`.```orders```");
    }

    @Test
    void copyFailurePreservesTheDatabaseMessageInEveryLocale() throws Exception {
        Connection connection = recordingConnection();
        SQLException original = new SQLException("Table 'app.orders' doesn't exist", "42S02", 1146);
        when(connection.prepareStatement(anyString())).thenThrow(original);
        BusinessException failure;
        try (MockedStatic<Chat2DBContext> context = context(connection, new MysqlDBManager(), new MysqlMetaData())) {
            failure = assertThrows(BusinessException.class, () -> service.copyTable(request("orders", "copy", true)));
        }
        assertEquals("table.copy.failed", failure.getCode());
        assertEquals(original.getMessage(), failure.getArgs()[0]);
        assertSame(original, failure.getCause().getCause());
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        for (Locale locale : List.of(Locale.ROOT, Locale.US, Locale.CHINA, Locale.JAPAN, Locale.KOREA, Locale.forLanguageTag("es-ES"))) {
            String text = messages.getMessage(failure.getCode(), failure.getArgs(), locale);
            assertTrue(text.contains(original.getMessage()), text);
            assertFalse(text.contains("no message"), text);
            assertFalse(text.contains("{0}"), text);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh-CN", "en-US", "ja-JP"})
    void desktopBridgeIncludesTheOriginalDatabaseError(String language) throws Exception {
        Connection connection = recordingConnection();
        String reason = "Table 'app.orders' doesn't exist";
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException(reason));
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        Locale previousLocale = LocaleContextHolder.getLocale();
        try (MockedStatic<Chat2DBContext> context = context(connection, new MysqlDBManager(), new MysqlMetaData());
             MockedStatic<RequestMappingUtils> routing = mockStatic(RequestMappingUtils.class);
             MockedStatic<ApplicationContextUtil> beans = mockStatic(ApplicationContextUtil.class);
             MockedStatic<I18nUtils> i18n = mockStatic(I18nUtils.class)) {
            RequestMappingInfo mapping = RequestMappingInfo.builder().controller(CopyController.class)
                    .method("copy").params(new Class<?>[0]).build();
            routing.when(() -> RequestMappingUtils.getRequestMappingInfo("/api/rdb/table/copy", "post")).thenReturn(mapping);
            beans.when(() -> ApplicationContextUtil.getBeanOfType(CopyController.class)).thenReturn(new CopyController());
            i18n.when(() -> I18nUtils.getMessage(eq("table.copy.failed"), any(Object[].class)))
                    .thenAnswer(call -> messages.getMessage(call.getArgument(0), call.getArgument(1), LocaleContextHolder.getLocale()));
            ConsoleMessage message = new ConsoleMessage();
            message.setRequestUrl("/api/rdb/table/copy");
            message.setMethod("post");
            message.setHeaders(Map.of("Accept-Language", language));
            Map<String, Object> result = ConsoleHelper.doController(message).getMessage();
            assertEquals("table.copy.failed", result.get("errorCode"));
            assertEquals(messages.getMessage("table.copy.failed", new Object[]{reason}, Locale.forLanguageTag(language)),
                    result.get("errorMessage"));
        } finally {
            LocaleContextHolder.setLocale(previousLocale);
        }
    }

    public static class CopyController {
        public void copy() {
            new DbTableServiceImpl(null).copyTable(request("orders", "copy", true));
        }
    }

    private static Connection recordingConnection() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        return connection;
    }

    private static MockedStatic<Chat2DBContext> context(Connection connection, IDbManager manager, IDbMetaData metadata) {
        MockedStatic<Chat2DBContext> context = mockStatic(Chat2DBContext.class);
        context.when(Chat2DBContext::getConnection).thenReturn(connection);
        context.when(Chat2DBContext::getDbManager).thenReturn(manager);
        context.when(Chat2DBContext::getDbMetaData).thenReturn(metadata);
        return context;
    }

    private static DbTableCopyRequest request(String source, String target, boolean copyData) {
        DbTableCopyRequest request = new DbTableCopyRequest();
        request.setDatabaseName("app");
        request.setTableName(source);
        request.setNewName(target);
        request.setCopyData(copyData);
        return request;
    }
}
