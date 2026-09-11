package ai.chat2db.community.domain.core.impl.ncx.dbeaver;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the credential file of an imported DBeaver project, decrypting it at most once.
 * <p>
 * DBeaver encrypts {@code credentials-config.json} with AES/CBC, using either its built-in local
 * key or, when the user configured a master password, a key derived from that password. This
 * resolver tries the local key first and the master password second. When neither key fits, or the
 * file is missing, empty, or unreadable, credentials are reported as unavailable and the caller
 * imports the connections without a user or password - which is what the import dialog already
 * promises, since connection passwords may have to be re-entered after an import.
 * <p>
 * An archive holds one credential file per project but many connections per project, so the
 * decrypted document is cached per file, including the "unavailable" outcome. Each credential file
 * is therefore read, decrypted, and parsed once per import instead of once per connection.
 * <p>
 * Instances are scoped to a single import and are not thread-safe. The master password and the
 * decrypted credentials live only as long as the instance: neither is written to disk, logged, or
 * cached statically.
 */
@Slf4j
public class DbeaverCredentialsResolver {

    private final String masterPassword;

    private final Map<String, JSONObject> cache = new HashMap<>();

    /**
     * @param masterPassword optional DBeaver master password. When blank, only the built-in local
     *                       key is tried.
     */
    public DbeaverCredentialsResolver(String masterPassword) {
        this.masterPassword = masterPassword;
    }

    /**
     * Returns the decrypted credentials of one project, or {@code null} when they are unavailable.
     * The first call for a file does the work; every later call reuses the same outcome.
     *
     * @param credentialsFile the project's {@code credentials-config.json}.
     * @return decrypted credentials document, or {@code null} when credentials cannot be read.
     */
    public JSONObject resolve(File credentialsFile) {
        String cacheKey = credentialsFile.getPath();
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }
        JSONObject credentials = read(credentialsFile);
        cache.put(cacheKey, credentials);
        return credentials;
    }

    private JSONObject read(File credentialsFile) {
        if (!credentialsFile.isFile()) {
            log.warn("DBeaver credential file is missing, connections are imported without passwords: {}",
                    credentialsFile.getName());
            return null;
        }
        byte[] encrypted;
        try {
            encrypted = Files.readAllBytes(credentialsFile.toPath());
        } catch (IOException e) {
            // impl-contract: fallback - an unreadable credential file imports connections without passwords.
            log.warn("Failed to read DBeaver credential file, connections are imported without passwords: {}",
                    e.getMessage());
            return null;
        }
        JSONObject credentials = decrypt(DefaultValueEncryptor.getLocalSecretKey(), encrypted);
        if (null != credentials) {
            return credentials;
        }
        if (StringUtils.isNotBlank(masterPassword)) {
            credentials = decrypt(DefaultValueEncryptor.makeSecretKeyFromPassword(masterPassword), encrypted);
            if (null != credentials) {
                log.info("DBeaver credentials decrypted with the supplied master password.");
                return credentials;
            }
        }
        log.warn("Unable to decrypt DBeaver credentials, connections are imported without passwords.");
        return null;
    }

    /**
     * Decrypts and parses the credential file with one candidate key. A key that does not fit
     * yields {@code null} so the caller can try the next one; an empty or non-object document is
     * treated the same way.
     */
    private JSONObject decrypt(SecretKey secretKey, byte[] encrypted) {
        try {
            return JSON.parseObject(new DefaultValueEncryptor(secretKey).decryptValue(encrypted));
        } catch (Exception e) {
            // impl-contract: fallback - a key that does not fit is expected; the caller tries the next key.
            log.debug("DBeaver credentials did not decrypt with the candidate key: {}", e.getMessage());
            return null;
        }
    }
}
