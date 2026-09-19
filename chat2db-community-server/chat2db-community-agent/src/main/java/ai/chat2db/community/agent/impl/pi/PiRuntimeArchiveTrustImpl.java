package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.agent.pi.IPiRuntimeArchiveTrust;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Function;

public class PiRuntimeArchiveTrustImpl implements IPiRuntimeArchiveTrust {

    private final Function<String, String> trustedSha256;

    public PiRuntimeArchiveTrustImpl(Function<String, String> trustedSha256) {
        this.trustedSha256 = trustedSha256;
    }

    @Override
    public void verify(String platform, byte[] archive) throws IOException {
        String expected = trustedSha256.apply(platform);
        if (expected == null || !expected.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("Trusted Pi runtime archive SHA-256 is not configured for " + platform);
        }
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(archive);
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(expected), actual)) {
                throw new IOException("Pi runtime archive does not match its trusted SHA-256");
            }
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
