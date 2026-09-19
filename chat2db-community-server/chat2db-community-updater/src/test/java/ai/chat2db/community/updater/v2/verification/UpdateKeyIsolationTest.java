package ai.chat2db.community.updater.v2.verification;

import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UpdateKeyIsolationTest {
    @Test
    void loadsOnlyTheSelectedProductsConfiguredTrustRoot() throws Exception {
        var generator = KeyPairGenerator.getInstance("Ed25519");
        var first = generator.generateKeyPair().getPublic();
        var second = generator.generateKeyPair().getPublic();
        // A key configured under another prefix must never be trusted.
        String otherProductPrefix = "chat2db.other-product.update";
        var keys = java.util.List.of("chat2db.update.key-id", "chat2db.update.public-key",
            otherProductPrefix + ".key-id", otherProductPrefix + ".public-key");
        var original = new java.util.HashMap<String, String>();
        keys.forEach(key -> original.put(key, System.getProperty(key)));
        try {
            System.setProperty("chat2db.update.key-id", "community-key");
            System.setProperty("chat2db.update.public-key", Base64.getEncoder().encodeToString(first.getEncoded()));
            System.setProperty(otherProductPrefix + ".key-id", "other-key");
            System.setProperty(otherProductPrefix + ".public-key",
                Base64.getEncoder().encodeToString(second.getEncoded()));
            var community = TrustedUpdateKeys.load("/absent.properties");
            assertEquals(first, community.get("community-key"));
            assertFalse(community.containsKey("other-key"));
            assertEquals(second,
                TrustedUpdateKeys.load("/absent.properties", otherProductPrefix).get("other-key"));
        } finally {
            original.forEach((key, value) -> {
                if (value == null) System.clearProperty(key);
                else System.setProperty(key, value);
            });
        }
    }
}
