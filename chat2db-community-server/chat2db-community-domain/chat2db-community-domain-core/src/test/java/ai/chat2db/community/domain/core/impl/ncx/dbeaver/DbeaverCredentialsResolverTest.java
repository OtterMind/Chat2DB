package ai.chat2db.community.domain.core.impl.ncx.dbeaver;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DbeaverCredentialsResolverTest {

    private static final String MASTER_PASSWORD = "s3cret-master";

    private static final String CREDENTIALS = "{\"mysql-1\":{\"#connection\":{\"user\":\"root\",\"password\":\"pwd\"}}}";

    @TempDir
    Path tempDir;

    @Test
    void decryptsCredentialsEncryptedWithTheDefaultLocalKey() throws Exception {
        File credentialsFile = credentialsFile(DefaultValueEncryptor.getLocalSecretKey(), CREDENTIALS);

        JSONObject credentials = new DbeaverCredentialsResolver(null).resolve(credentialsFile);

        assertEquals("root", user(credentials, "mysql-1"));
    }

    @Test
    void decryptsCredentialsEncryptedWithTheMasterPasswordKey() throws Exception {
        File credentialsFile = credentialsFile(
                DefaultValueEncryptor.makeSecretKeyFromPassword(MASTER_PASSWORD), CREDENTIALS);

        JSONObject credentials = new DbeaverCredentialsResolver(MASTER_PASSWORD).resolve(credentialsFile);

        assertEquals("root", user(credentials, "mysql-1"));
    }

    @Test
    void reportsCredentialsUnavailableWhenTheMasterPasswordIsMissingOrWrong() throws Exception {
        File credentialsFile = credentialsFile(
                DefaultValueEncryptor.makeSecretKeyFromPassword(MASTER_PASSWORD), CREDENTIALS);

        assertNull(new DbeaverCredentialsResolver(null).resolve(credentialsFile));
        assertNull(new DbeaverCredentialsResolver(" ").resolve(credentialsFile));
        assertNull(new DbeaverCredentialsResolver("another-password").resolve(credentialsFile));
    }

    @Test
    void reportsCredentialsUnavailableWhenTheFileIsMissing() {
        File missing = tempDir.resolve("absent").resolve("credentials-config.json").toFile();

        assertNull(new DbeaverCredentialsResolver(MASTER_PASSWORD).resolve(missing));
    }

    @Test
    void reportsCredentialsUnavailableForMalformedFiles() throws Exception {
        File notEncrypted = tempDir.resolve("plain-credentials-config.json").toFile();
        Files.write(notEncrypted.toPath(), CREDENTIALS.getBytes(StandardCharsets.UTF_8));
        File empty = tempDir.resolve("empty-credentials-config.json").toFile();
        Files.write(empty.toPath(), new byte[0]);
        File truncated = tempDir.resolve("truncated-credentials-config.json").toFile();
        Files.write(truncated.toPath(), new byte[]{1, 2, 3});
        File encryptedGarbage = credentialsFile(DefaultValueEncryptor.getLocalSecretKey(), "not json at all");

        DbeaverCredentialsResolver resolver = new DbeaverCredentialsResolver(MASTER_PASSWORD);

        assertNull(resolver.resolve(notEncrypted));
        assertNull(resolver.resolve(empty));
        assertNull(resolver.resolve(truncated));
        assertNull(resolver.resolve(encryptedGarbage));
    }

    @Test
    void decryptsEachCredentialFileOnlyOnce() throws Exception {
        File credentialsFile = credentialsFile(DefaultValueEncryptor.getLocalSecretKey(), CREDENTIALS);
        DbeaverCredentialsResolver resolver = new DbeaverCredentialsResolver(null);

        JSONObject first = resolver.resolve(credentialsFile);
        // Deleting the source proves the second lookup is served from the cache instead of the disk.
        Files.delete(credentialsFile.toPath());
        JSONObject second = resolver.resolve(credentialsFile);

        assertNotNull(first);
        assertSame(first, second);
    }

    @Test
    void cachesTheUnavailableOutcomeOfEachCredentialFile() throws Exception {
        File credentialsFile = credentialsFile(
                DefaultValueEncryptor.makeSecretKeyFromPassword(MASTER_PASSWORD), CREDENTIALS);
        DbeaverCredentialsResolver resolver = new DbeaverCredentialsResolver(null);

        assertNull(resolver.resolve(credentialsFile));
        // A later matching key must not revive a file already reported as unavailable in this import.
        assertNull(resolver.resolve(credentialsFile));
    }

    @Test
    void keepsCredentialsOfDistinctProjectsApart() throws Exception {
        File first = credentialsFile(DefaultValueEncryptor.getLocalSecretKey(), CREDENTIALS,
                "first-credentials-config.json");
        File second = credentialsFile(DefaultValueEncryptor.getLocalSecretKey(),
                "{\"pg-1\":{\"#connection\":{\"user\":\"postgres\",\"password\":\"pwd\"}}}",
                "second-credentials-config.json");
        DbeaverCredentialsResolver resolver = new DbeaverCredentialsResolver(null);

        assertEquals("root", user(resolver.resolve(first), "mysql-1"));
        assertEquals("postgres", user(resolver.resolve(second), "pg-1"));
    }

    private File credentialsFile(SecretKey secretKey, String content) throws Exception {
        return credentialsFile(secretKey, content, "credentials-config.json");
    }

    private File credentialsFile(SecretKey secretKey, String content, String fileName) throws Exception {
        File credentialsFile = tempDir.resolve(fileName).toFile();
        byte[] encrypted = new DefaultValueEncryptor(secretKey).encryptValue(content.getBytes(StandardCharsets.UTF_8));
        Files.write(credentialsFile.toPath(), encrypted);
        return credentialsFile;
    }

    private static String user(JSONObject credentials, String connectionId) {
        assertNotNull(credentials);
        return credentials.getJSONObject(connectionId).getJSONObject("#connection").getString("user");
    }
}
