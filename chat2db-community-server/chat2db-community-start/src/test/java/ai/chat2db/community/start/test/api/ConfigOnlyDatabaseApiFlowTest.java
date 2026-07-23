package ai.chat2db.community.start.test.api;

import ai.chat2db.community.start.test.common.BaseTest;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Full-stack API flow for a configuration-only database (HSQLDB from
 * generic.json), exercised through the real controllers over HTTP:
 * pre_connect (connection test), datasource create, database listing,
 * schema listing, SQL execution (DDL/DML/query), and table listing.
 *
 * The Spring context boots against a throwaway user.home with an inline
 * encryption key; the HSQLDB driver jar is pre-seeded into jdbc-lib from
 * the local Maven repository so no network is needed. The test skips when
 * that jar is unavailable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConfigOnlyDatabaseApiFlowTest extends BaseTest {

    private static final String DB_URL = "jdbc:hsqldb:mem:c2d_api_flow";
    private static final String TABLE = "C2D_API_FLOW";
    private static boolean driverSeeded;

    static {
        try {
            String originalHome = System.getProperty("user.home");
            Path home = Files.createTempDirectory("c2d-api-flow-home");
            System.setProperty("user.home", home.toString());
            System.setProperty("chat2db.runtime.mode", "community");
            System.setProperty("chat2db.network.status", "OFFLINE");
            System.setProperty("chat2db.community.encryption-key",
                    Base64.getEncoder().encodeToString(
                            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
            Path m2Jar = Path.of(originalHome, ".m2", "repository", "org", "hsqldb", "hsqldb",
                    "2.7.3", "hsqldb-2.7.3.jar");
            if (Files.isRegularFile(m2Jar)) {
                Path libDir = home.resolve(".chat2db-community").resolve("jdbc-lib");
                Files.createDirectories(libDir);
                Files.copy(m2Jar, libDir.resolve("hsqldb-2.7.3.jar"));
                driverSeeded = true;
            }
        } catch (IOException e) {
            driverSeeded = false;
        }
    }

    @Autowired
    private TestRestTemplate rest;

    private JSONObject post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String response = rest.postForEntity(path, new HttpEntity<>(body, headers), String.class).getBody();
        assertNotNull(response, path + " should return a body");
        return JSONObject.parseObject(response);
    }

    private JSONObject get(String pathWithQuery) {
        String response = rest.getForEntity(pathWithQuery, String.class).getBody();
        assertNotNull(response, pathWithQuery + " should return a body");
        return JSONObject.parseObject(response);
    }

    private void assertSuccess(JSONObject result, String step) {
        assertTrue(result.getBooleanValue("success"), step + " failed: " + result.toJSONString());
    }

    private Map<String, Object> connectionBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "HSQLDB");
        body.put("url", DB_URL);
        body.put("user", "SA");
        body.put("password", "");
        // The frontend always sends an ssh block; the backend dereferences it.
        body.put("ssh", Map.of("use", false));
        return body;
    }

    private long createDataSource(String alias) {
        Map<String, Object> body = connectionBody();
        body.put("alias", alias);
        body.put("environmentId", 1);
        JSONObject created = post("/api/connection/datasource/create", body);
        assertSuccess(created, "datasource create");
        return created.getJSONObject("data").getLongValue("id");
    }

    private JSONObject executeSql(long dataSourceId, String sql) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataSourceId", dataSourceId);
        body.put("sql", sql);
        body.put("tableName", "");
        return post("/api/rdb/dml/execute", body);
    }

    @Test
    void configOnlyDatabaseServesFullApiFlow() {
        assumeTrue(driverSeeded, "hsqldb jar not found in local Maven repository; skipping");

        // 1. Connection test through the real pre_connect endpoint.
        JSONObject preConnect = post("/api/connection/datasource/pre_connect", connectionBody());
        assertSuccess(preConnect, "pre_connect");

        // 2. Create the datasource. The connection aspect only binds ids > 1,
        //    so make sure the id under test is beyond that threshold.
        long dataSourceId = createDataSource("c2d-api-flow");
        if (dataSourceId <= 1) {
            dataSourceId = createDataSource("c2d-api-flow-2");
        }
        assertTrue(dataSourceId > 1, "datasource id should be usable by the connection aspect");

        // 3. Database listing.
        JSONObject databases = get("/api/rdb/database/list?dataSourceId=" + dataSourceId);
        assertSuccess(databases, "database list");

        // 4. Schema listing contains HSQLDB's PUBLIC schema.
        JSONObject schemas = get("/api/rdb/schema/list?dataSourceId=" + dataSourceId);
        assertSuccess(schemas, "schema list");
        JSONArray schemaList = schemas.getJSONArray("data");
        assertNotNull(schemaList, "schema list data");
        assertTrue(schemaList.stream().anyMatch(item ->
                        "PUBLIC".equals(((JSONObject) item).getString("name"))),
                "schemas should contain PUBLIC: " + schemaList.toJSONString());

        // 5. SQL execution: DDL, DML, and query through the dml controller.
        assertSuccess(executeSql(dataSourceId,
                "CREATE TABLE " + TABLE + " (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(50))"), "create table");
        assertSuccess(executeSql(dataSourceId,
                "INSERT INTO " + TABLE + " (ID, NAME) VALUES (1, 'chat2db')"), "insert");
        JSONObject query = executeSql(dataSourceId, "SELECT ID, NAME FROM " + TABLE);
        assertSuccess(query, "query");
        JSONArray results = query.getJSONArray("data");
        assertNotNull(results, "query result list");
        assertTrue(results.size() > 0, "query should return one result block");
        assertTrue(((JSONObject) results.get(0)).getBooleanValue("success"),
                "query result should be successful: " + results.toJSONString());

        // 6. Table listing sees the created table.
        JSONObject tables = get("/api/rdb/table/table_list?dataSourceId=" + dataSourceId
                + "&schemaName=PUBLIC&pageNo=1&pageSize=100");
        assertSuccess(tables, "table list");
        JSONArray tableList = tables.getJSONArray("data");
        assertNotNull(tableList, "table list data");
        assertTrue(tableList.stream().anyMatch(item ->
                        TABLE.equals(((JSONObject) item).getString("name"))),
                "tables should contain " + TABLE + ": " + tableList.toJSONString());
    }
}
