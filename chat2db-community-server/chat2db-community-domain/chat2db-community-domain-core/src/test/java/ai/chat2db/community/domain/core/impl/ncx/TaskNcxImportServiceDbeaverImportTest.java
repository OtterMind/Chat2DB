package ai.chat2db.community.domain.core.impl.ncx;

import ai.chat2db.community.domain.api.model.ncx.NcxImportResponse;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.core.impl.ncx.dbeaver.DefaultValueEncryptor;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the DBeaver (.dbp) import path: which keys decrypt the credential file, what happens when
 * a connection has no credentials, and that no extracted project survives the import.
 */
class TaskNcxImportServiceDbeaverImportTest {

    private static final String MASTER_PASSWORD = "s3cret-master";

    private static final String CONFIG_DIR = ".dbeaver";

    private static String originalUserHome;

    /**
     * The import extracts into {@link ConfigUtils#getBasePath()}, which is derived from
     * {@code user.home}. The redirect target outlives the class on purpose: {@code ConfigUtils}
     * caches files from its static initializer, so deleting the directory afterwards would leave
     * those fields pointing at a removed path for the rest of the JVM.
     */
    @BeforeAll
    static void redirectUserHome() throws IOException {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("chat2db-dbeaver-import-home").toString());
        ConfigUtils.getBasePath();
    }

    @AfterAll
    static void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @TempDir
    Path tempDir;

    @Test
    void importsCredentialsDecryptedWithTheDefaultLocalKey() throws Exception {
        List<WorkspaceDataSource> imported = new ArrayList<>();
        File archive = archive("default-key.dbp", Map.of("general",
                project(dataSources("mysql-1", "mysql-2"),
                        encrypt(DefaultValueEncryptor.getLocalSecretKey(), credentials("mysql-1", "mysql-2")))));

        NcxImportResponse response = service(imported).dbpUploadFile(archive, null);

        assertEquals(2, response.getCount());
        assertEquals("user-mysql-1", byAlias(imported, "mysql-1").getUser());
        assertEquals("pwd-mysql-1", byAlias(imported, "mysql-1").getPassword());
        assertEquals("user-mysql-2", byAlias(imported, "mysql-2").getUser());
        assertEquals("MYSQL", byAlias(imported, "mysql-1").getType());
        assertEquals("3306", byAlias(imported, "mysql-1").getPort());
    }

    @Test
    void importsCredentialsDecryptedWithTheSuppliedMasterPassword() throws Exception {
        List<WorkspaceDataSource> imported = new ArrayList<>();
        File archive = archive("master-password.dbp", Map.of("general",
                project(dataSources("mysql-1"),
                        encrypt(DefaultValueEncryptor.makeSecretKeyFromPassword(MASTER_PASSWORD),
                                credentials("mysql-1")))));

        NcxImportResponse response = service(imported).dbpUploadFile(archive, MASTER_PASSWORD);

        assertEquals(1, response.getCount());
        assertEquals("user-mysql-1", byAlias(imported, "mysql-1").getUser());
        assertEquals("pwd-mysql-1", byAlias(imported, "mysql-1").getPassword());
    }

    @Test
    void importsConnectionsWithoutPasswordsWhenCredentialsCannotBeDecrypted() throws Exception {
        List<WorkspaceDataSource> imported = new ArrayList<>();
        File archive = archive("undecryptable.dbp", Map.of("general",
                project(dataSources("mysql-1"),
                        encrypt(DefaultValueEncryptor.makeSecretKeyFromPassword(MASTER_PASSWORD),
                                credentials("mysql-1")))));

        // No master password supplied, so the credential file stays encrypted: the import must not fail.
        NcxImportResponse response = service(imported).dbpUploadFile(archive, null);

        assertEquals(1, response.getCount());
        assertNull(byAlias(imported, "mysql-1").getUser());
        assertNull(byAlias(imported, "mysql-1").getPassword());
    }

    @Test
    void importsConnectionsWithoutPasswordsWhenTheCredentialFileIsAbsent() throws Exception {
        List<WorkspaceDataSource> imported = new ArrayList<>();
        File archive = archive("no-credentials.dbp", Map.of("general",
                project(dataSources("mysql-1"), null)));

        NcxImportResponse response = service(imported).dbpUploadFile(archive, MASTER_PASSWORD);

        assertEquals(1, response.getCount());
        assertNull(byAlias(imported, "mysql-1").getUser());
    }

    @Test
    void importsConnectionsWhoseCredentialEntryIsMissingOrMalformed() throws Exception {
        List<WorkspaceDataSource> imported = new ArrayList<>();
        // "mysql-2" has no entry at all - DBeaver omits connections saved without a password.
        // "mysql-3" has an entry of the wrong shape, which must not abort the whole import either.
        String credentials = "{\"mysql-1\":{\"#connection\":{\"user\":\"user-mysql-1\",\"password\":\"pwd-mysql-1\"}},"
                + "\"mysql-3\":\"malformed\"}";
        File archive = archive("partial-credentials.dbp", Map.of("general",
                project(dataSources("mysql-1", "mysql-2", "mysql-3"),
                        encrypt(DefaultValueEncryptor.getLocalSecretKey(), credentials))));

        NcxImportResponse response = service(imported).dbpUploadFile(archive, null);

        assertEquals(3, response.getCount());
        assertEquals("user-mysql-1", byAlias(imported, "mysql-1").getUser());
        assertNull(byAlias(imported, "mysql-2").getUser());
        assertNull(byAlias(imported, "mysql-2").getPassword());
        assertNull(byAlias(imported, "mysql-3").getUser());
        assertNull(byAlias(imported, "mysql-3").getPassword());
    }

    @Test
    void removesEveryExtractedProjectAndTheUploadAfterImport() throws Exception {
        List<WorkspaceDataSource> imported = new ArrayList<>();
        Map<String, Map<String, byte[]>> projects = new LinkedHashMap<>();
        projects.put("alpha", project(dataSources("mysql-1"),
                encrypt(DefaultValueEncryptor.getLocalSecretKey(), credentials("mysql-1"))));
        projects.put("beta", project(dataSources("mysql-2"),
                encrypt(DefaultValueEncryptor.getLocalSecretKey(), credentials("mysql-2"))));
        File archive = archive("two-projects.dbp", projects);

        NcxImportResponse response = service(imported).dbpUploadFile(archive, null);

        assertEquals(2, response.getCount());
        assertEquals("user-mysql-1", byAlias(imported, "mysql-1").getUser());
        assertEquals("user-mysql-2", byAlias(imported, "mysql-2").getUser());
        assertFalse(extractedProject("alpha").exists());
        assertFalse(extractedProject("beta").exists());
        assertFalse(archive.exists());
    }

    @Test
    void removesEveryExtractedProjectAndTheUploadWhenTheImportFails() throws Exception {
        List<WorkspaceDataSource> imported = new ArrayList<>();
        Map<String, Map<String, byte[]>> projects = new LinkedHashMap<>();
        projects.put("valid", project(dataSources("mysql-1"),
                encrypt(DefaultValueEncryptor.getLocalSecretKey(), credentials("mysql-1"))));
        projects.put("corrupt", project("{ not json".getBytes(StandardCharsets.UTF_8),
                encrypt(DefaultValueEncryptor.getLocalSecretKey(), credentials("mysql-2"))));
        File archive = archive("corrupt-project.dbp", projects);
        TaskNcxImportServiceImpl service = service(imported);

        assertThrows(RuntimeException.class, () -> service.dbpUploadFile(archive, null));

        assertFalse(extractedProject("valid").exists());
        assertFalse(extractedProject("corrupt").exists());
        assertFalse(archive.exists());
    }

    private TaskNcxImportServiceImpl service(List<WorkspaceDataSource> imported) {
        return new TaskNcxImportServiceImpl(storageFacade(imported));
    }

    private static IWorkspaceStorageFacade storageFacade(List<WorkspaceDataSource> imported) {
        return (IWorkspaceStorageFacade) Proxy.newProxyInstance(
                IWorkspaceStorageFacade.class.getClassLoader(),
                new Class<?>[]{IWorkspaceStorageFacade.class},
                (proxy, method, args) -> {
                    if ("createDataSource".equals(method.getName())) {
                        imported.add((WorkspaceDataSource) args[0]);
                        return 1L;
                    }
                    return null;
                });
    }

    private static File extractedProject(String projectName) {
        return new File(ConfigUtils.getBasePath() + File.separator + projectName);
    }

    private static WorkspaceDataSource byAlias(List<WorkspaceDataSource> imported, String alias) {
        return imported.stream()
                .filter(dataSource -> alias.equals(dataSource.getAlias()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("connection was not imported: " + alias));
    }

    /**
     * Builds the {@code .dbeaver} payload of one project. A null credential file mirrors an archive
     * exported from a DBeaver workspace that stores no passwords.
     */
    private static Map<String, byte[]> project(byte[] dataSources, byte[] credentials) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(ExportConstants.CONFIG_DATASOURCE_FILE, dataSources);
        if (null != credentials) {
            files.put(ExportConstants.CONFIG_CREDENTIALS_FILE, credentials);
        }
        return files;
    }

    private static byte[] dataSources(String... connectionIds) {
        StringBuilder json = new StringBuilder("{\"connections\":{");
        for (int i = 0; i < connectionIds.length; i++) {
            json.append(0 == i ? "" : ",")
                    .append("\"").append(connectionIds[i]).append("\":{")
                    .append("\"provider\":\"mysql\",")
                    .append("\"name\":\"").append(connectionIds[i]).append("\",")
                    .append("\"configuration\":{\"host\":\"127.0.0.1\",\"port\":\"3306\",")
                    .append("\"url\":\"jdbc:mysql://127.0.0.1:3306/demo\"}}");
        }
        return json.append("}}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String credentials(String... connectionIds) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < connectionIds.length; i++) {
            json.append(0 == i ? "" : ",")
                    .append("\"").append(connectionIds[i]).append("\":{\"#connection\":{")
                    .append("\"user\":\"user-").append(connectionIds[i]).append("\",")
                    .append("\"password\":\"pwd-").append(connectionIds[i]).append("\"}}");
        }
        return json.append("}").toString();
    }

    private static byte[] encrypt(SecretKey secretKey, String credentials) {
        return new DefaultValueEncryptor(secretKey).encryptValue(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes a .dbp archive: a {@code meta.xml} describing each project's {@code .dbeaver} resource
     * tree, plus the matching entries under {@code projects/}.
     */
    private File archive(String archiveName, Map<String, Map<String, byte[]>> projects) throws IOException {
        File archive = tempDir.resolve(archiveName).toFile();
        try (OutputStream out = Files.newOutputStream(archive.toPath());
             ZipOutputStream zip = new ZipOutputStream(out)) {
            StringBuilder meta = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .append("<archive version=\"1.0\"><projects>");
            for (Map.Entry<String, Map<String, byte[]>> project : projects.entrySet()) {
                meta.append("<project name=\"").append(project.getKey()).append("\">")
                        .append("<resource name=\"").append(CONFIG_DIR).append("\">");
                for (String fileName : project.getValue().keySet()) {
                    meta.append("<resource name=\"").append(fileName).append("\"/>");
                }
                meta.append("</resource></project>");
            }
            meta.append("</projects></archive>");
            writeEntry(zip, ExportConstants.META_FILENAME, meta.toString().getBytes(StandardCharsets.UTF_8));

            for (Map.Entry<String, Map<String, byte[]>> project : projects.entrySet()) {
                String projectPath = ExportConstants.DIR_PROJECTS + "/" + project.getKey() + "/" + CONFIG_DIR + "/";
                zip.putNextEntry(new ZipEntry(projectPath));
                zip.closeEntry();
                for (Map.Entry<String, byte[]> file : project.getValue().entrySet()) {
                    writeEntry(zip, projectPath + file.getKey(), file.getValue());
                }
            }
        }
        return archive;
    }

    private static void writeEntry(ZipOutputStream zip, String path, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content);
        zip.closeEntry();
    }
}
