package ai.chat2db.plugin.mysql.partition;

import ai.chat2db.community.domain.api.model.metadata.TablePartition;
import ai.chat2db.plugin.mysql.MysqlPlugin;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlPartitionManagerTest {

    @Test
    void mysqlPluginExposesPartitionManager() {
        assertTrue(new MysqlPlugin().getPartitionManager() instanceof MysqlPartitionManager);
    }

    private IPlugin previousMysqlPlugin;

    @BeforeEach
    void setUp() {
        previousMysqlPlugin = Chat2DBContext.PLUGIN_MAP.put(DatabaseTypeEnum.MYSQL.name(), mysqlPlugin());
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36", connection(new HashMap<>())));
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousMysqlPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DatabaseTypeEnum.MYSQL.name());
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DatabaseTypeEnum.MYSQL.name(), previousMysqlPlugin);
        }
    }

    @Test
    void listBindsInformationSchemaLookupToTheRequestedDatabaseAndTable() {
        Map<Integer, String> parameters = new HashMap<>();
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36", connection(parameters)));

        assertTrue(new MysqlPartitionManager().list("orders_db", "orders").isEmpty());

        assertEquals("orders_db", parameters.get(1));
        assertEquals("orders", parameters.get(2));
    }

    @Test
    void listReturnsCompletePartitionMetadataForTreeAndTableDesignerReadback() {
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), List.of(fullMetadataRow()))));

        TablePartition partition = new MysqlPartitionManager().list("orders_db", "orders").get(0);

        assertEquals("p_mid", partition.getPartitionName());
        assertEquals("sp0", partition.getSubpartitionName());
        assertEquals(2L, partition.getOrdinalPosition());
        assertEquals(1L, partition.getSubpartitionOrdinalPosition());
        assertEquals("RANGE COLUMNS", partition.getMethod());
        assertEquals("HASH", partition.getSubpartitionMethod());
        assertEquals("store_id,sale_date", partition.getExpression());
        assertEquals("id", partition.getSubpartitionExpression());
        assertEquals("20,'2026-01-01'", partition.getDescription());
        assertEquals(12L, partition.getTableRows());
        assertEquals(128L, partition.getAvgRowLength());
        assertEquals(1536L, partition.getDataLength());
        assertEquals(4096L, partition.getMaxDataLength());
        assertEquals(512L, partition.getIndexLength());
        assertEquals(64L, partition.getDataFree());
        assertEquals("2026-01-02 03:04:05", partition.getCreateTime());
        assertEquals("2026-01-03 04:05:06", partition.getUpdateTime());
        assertEquals("2026-01-04 05:06:07", partition.getCheckTime());
        assertEquals(99L, partition.getChecksum());
        assertEquals("warm partition", partition.getComment());
        assertEquals("default", partition.getNodegroup());
        assertEquals("ts_hot", partition.getTablespaceName());
    }

    @Test
    void listPreservesAllSupportedMysqlPartitionMethods() {
        List<String> methods = List.of("RANGE", "RANGE COLUMNS", "LIST", "LIST COLUMNS",
                "HASH", "LINEAR HASH", "KEY", "LINEAR KEY");
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), methods.stream()
                        .map(method -> {
                            Map<String, Object> row = baseRow();
                            row.put("PARTITION_NAME", "p_" + method.replace(' ', '_').toLowerCase());
                            row.put("PARTITION_METHOD", method);
                            return row;
                        })
                        .toList())));

        List<String> actualMethods = new MysqlPartitionManager().list("orders_db", "orders").stream()
                .map(TablePartition::getMethod)
                .toList();

        assertEquals(methods, actualMethods);
    }

    @Test
    void destructivePreviewSqlQualifiesTableWithRequestedDatabase() {
        MysqlPartitionManager service = new MysqlPartitionManager();
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("RANGE", "p202401"))));

        assertEquals("ALTER TABLE `orders_db`.`orders` TRUNCATE PARTITION `p202401`",
                service.truncatePartitionSql("orders_db", "orders", "p202401"));
        assertEquals("ALTER TABLE `orders_db`.`orders` DROP PARTITION `p202401`",
                service.dropPartitionSql("orders_db", "orders", "p202401"));

        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("HASH", "p0"))));
        assertEquals("ALTER TABLE `orders_db`.`orders` COALESCE PARTITION 2",
                service.coalescePartitionSql("orders_db", "orders", 2));
    }

    @Test
    void addAndReorganizePreviewSqlFollowPartitionType() {
        MysqlPartitionManager service = new MysqlPartitionManager();
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("RANGE COLUMNS", "p2025", "p_future"))));

        assertEquals("ALTER TABLE `orders_db`.`orders` ADD PARTITION (PARTITION `p2026` VALUES LESS THAN (2027))",
                service.addPartitionSql("orders_db", "orders", "p2026", "VALUES LESS THAN (2027)", null));
        assertEquals("ALTER TABLE `orders_db`.`orders` REORGANIZE PARTITION `p_future` INTO "
                        + "(PARTITION p2026 VALUES LESS THAN (2027), PARTITION p_future VALUES LESS THAN MAXVALUE)",
                service.reorganizePartitionSql("orders_db", "orders", "p_future",
                        "PARTITION p2026 VALUES LESS THAN (2027), PARTITION p_future VALUES LESS THAN MAXVALUE"));
        assertThrows(BusinessException.class,
                () -> service.addPartitionSql("orders_db", "orders", "p_bad", "VALUES IN (1)", null));

        Map<String, Object> maxValuePartition = baseRow();
        maxValuePartition.put("PARTITION_NAME", "p_future");
        maxValuePartition.put("PARTITION_METHOD", "RANGE");
        maxValuePartition.put("PARTITION_DESCRIPTION", "MAXVALUE");
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), List.of(maxValuePartition))));
        assertThrows(BusinessException.class,
                () -> service.addPartitionSql("orders_db", "orders", "p2026", "VALUES LESS THAN (2027)", null));

        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("LINEAR HASH", "p0"))));

        assertEquals("ALTER TABLE `orders_db`.`orders` ADD PARTITION PARTITIONS 3",
                service.addPartitionSql("orders_db", "orders", null, null, 3));
        assertThrows(BusinessException.class,
                () -> service.reorganizePartitionSql("orders_db", "orders", "p0",
                        "PARTITION p0 VALUES LESS THAN MAXVALUE"));
    }

    @Test
    void operationPreviewsRejectUnsupportedPartitionTypes() {
        MysqlPartitionManager service = new MysqlPartitionManager();
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("HASH", "p0"))));

        assertThrows(BusinessException.class, () -> service.dropPartitionSql("orders_db", "orders", "p0"));
        assertThrows(BusinessException.class, () -> service.truncatePartitionSql("orders_db", "orders", "p0"));

        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("LIST", "p_east"))));

        assertThrows(BusinessException.class, () -> service.coalescePartitionSql("orders_db", "orders", 1));
    }

    @Test
    void listHidesMysqlNonPartitionedInformationSchemaRows() {
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), nonPartitionedRow())));

        assertTrue(new MysqlPartitionManager().list("orders_db", "orders").isEmpty());
    }

    @Test
    void maintenancePreviewSqlIsLimitedToSupportedOperations() {
        MysqlPartitionManager service = new MysqlPartitionManager();
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("RANGE", "p202401"))));

        assertEquals("ALTER TABLE `orders_db`.`orders` ANALYZE PARTITION `p202401`",
                service.maintainPartitionSql("orders_db", "orders", "analyze", "p202401"));
        assertEquals("ALTER TABLE `orders_db`.`orders` CHECK PARTITION ALL",
                service.maintainPartitionSql("orders_db", "orders", "CHECK", null));
        assertEquals("ALTER TABLE `orders_db`.`orders` OPTIMIZE PARTITION `p202401`",
                service.maintainPartitionSql("orders_db", "orders", "OPTIMIZE", "p202401"));
        assertThrows(BusinessException.class,
                () -> service.maintainPartitionSql("orders_db", "orders", "REPAIR", "p202401"));
    }

    @Test
    void partitionOperationsRequireMysql57OrNewer() {
        MysqlPartitionManager service = new MysqlPartitionManager();

        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "5.6.51", connection(new HashMap<>())));
        assertThrows(BusinessException.class, () -> service.coalescePartitionSql("orders_db", "orders", 1));

        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.POSTGRESQL.name(), "15.0", connection(new HashMap<>())));
        assertThrows(BusinessException.class, () -> service.coalescePartitionSql("orders_db", "orders", 1));
    }

    private static ConnectInfo connectInfo(String dbType, String dbVersion, Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(42L);
        connectInfo.setDbType(dbType);
        connectInfo.setDbVersion(dbVersion);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection);
        return connectInfo;
    }

    private static IPlugin mysqlPlugin() {
        DBConfig config = new DBConfig();
        config.setDbType(DatabaseTypeEnum.MYSQL.name());
        config.setDefaultDriverConfig(new DriverConfig());
        IDbMetaData metaData = new DefaultMetaService() {
            @Override
            public String getMetaDataName(String... names) {
                return Arrays.stream(names)
                        .filter(StringUtils::isNotBlank)
                        .map(name -> "`" + name.replace("`", "``") + "`")
                        .collect(Collectors.joining("."));
            }
        };
        return new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return metaData;
            }
        };
    }

    private static Connection connection(Map<Integer, String> parameters) {
        return connection(parameters, List.of());
    }

    private static Connection connection(Map<Integer, String> parameters, List<Map<String, Object>> rows) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "prepareStatement" -> preparedStatement(parameters, rows);
                    case "isClosed" -> false;
                    case "isValid" -> true;
                    case "close" -> null;
                    case "toString" -> "PartitionServiceTestConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private static PreparedStatement preparedStatement(Map<Integer, String> parameters, List<Map<String, Object>> rows) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "setString" -> {
                        parameters.put((Integer) arguments[0], (String) arguments[1]);
                        yield null;
                    }
                    case "executeQuery" -> resultSet(rows);
                    case "close" -> null;
                    case "toString" -> "PartitionServiceTestPreparedStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] rowIndex = {-1};
        Object[] lastValue = {null};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "next" -> ++rowIndex[0] < rows.size();
                    case "getString" -> {
                        Object value = rows.get(rowIndex[0]).get((String) arguments[0]);
                        lastValue[0] = value;
                        yield value == null ? null : value.toString();
                    }
                    case "getLong" -> {
                        Object value = rows.get(rowIndex[0]).get((String) arguments[0]);
                        lastValue[0] = value;
                        yield value instanceof Number ? ((Number) value).longValue() : 0L;
                    }
                    case "wasNull" -> lastValue[0] == null;
                    case "close" -> null;
                    case "toString" -> "PartitionServiceTestResultSet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private static List<Map<String, Object>> partitionRows(String method, String... partitionNames) {
        return Arrays.stream(partitionNames)
                .map(partitionName -> {
                    Map<String, Object> row = baseRow();
                    row.put("PARTITION_NAME", partitionName);
                    row.put("PARTITION_METHOD", method);
                    return row;
                })
                .toList();
    }

    private static List<Map<String, Object>> nonPartitionedRow() {
        return List.of(baseRow());
    }

    private static Map<String, Object> baseRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("PARTITION_NAME", null);
        row.put("SUBPARTITION_NAME", null);
        row.put("PARTITION_ORDINAL_POSITION", 1L);
        row.put("SUBPARTITION_ORDINAL_POSITION", null);
        row.put("PARTITION_METHOD", null);
        row.put("SUBPARTITION_METHOD", null);
        row.put("PARTITION_EXPRESSION", null);
        row.put("SUBPARTITION_EXPRESSION", null);
        row.put("PARTITION_DESCRIPTION", null);
        row.put("TABLE_ROWS", 0L);
        row.put("AVG_ROW_LENGTH", 0L);
        row.put("DATA_LENGTH", 0L);
        row.put("MAX_DATA_LENGTH", 0L);
        row.put("INDEX_LENGTH", 0L);
        row.put("DATA_FREE", 0L);
        row.put("CREATE_TIME", null);
        row.put("UPDATE_TIME", null);
        row.put("CHECK_TIME", null);
        row.put("CHECKSUM", null);
        row.put("PARTITION_COMMENT", null);
        row.put("NODEGROUP", null);
        row.put("TABLESPACE_NAME", null);
        return row;
    }

    private static Map<String, Object> fullMetadataRow() {
        Map<String, Object> row = baseRow();
        row.put("PARTITION_NAME", "p_mid");
        row.put("SUBPARTITION_NAME", "sp0");
        row.put("PARTITION_ORDINAL_POSITION", 2L);
        row.put("SUBPARTITION_ORDINAL_POSITION", 1L);
        row.put("PARTITION_METHOD", "RANGE COLUMNS");
        row.put("SUBPARTITION_METHOD", "HASH");
        row.put("PARTITION_EXPRESSION", "store_id,sale_date");
        row.put("SUBPARTITION_EXPRESSION", "id");
        row.put("PARTITION_DESCRIPTION", "20,'2026-01-01'");
        row.put("TABLE_ROWS", 12L);
        row.put("AVG_ROW_LENGTH", 128L);
        row.put("DATA_LENGTH", 1536L);
        row.put("MAX_DATA_LENGTH", 4096L);
        row.put("INDEX_LENGTH", 512L);
        row.put("DATA_FREE", 64L);
        row.put("CREATE_TIME", "2026-01-02 03:04:05");
        row.put("UPDATE_TIME", "2026-01-03 04:05:06");
        row.put("CHECK_TIME", "2026-01-04 05:06:07");
        row.put("CHECKSUM", 99L);
        row.put("PARTITION_COMMENT", "warm partition");
        row.put("NODEGROUP", "default");
        row.put("TABLESPACE_NAME", "ts_hot");
        return row;
    }
}
