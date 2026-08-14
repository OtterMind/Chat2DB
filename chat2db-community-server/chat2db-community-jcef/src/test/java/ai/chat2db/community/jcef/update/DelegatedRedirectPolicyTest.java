package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DelegatedRedirectPolicyTest {

    private static final URI GITHUB_PAYLOAD = URI.create(
            "https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar");
    private static final URI GITHUB_LATEST = URI.create(
            "https://github.com/OtterMind/Chat2DB/releases/latest/download/version.json");

    @Test
    void allowsRedirectFromLatestToVersionedAsset() throws IOException {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_LATEST);
        URI next = policy.nextUri(GITHUB_LATEST,
                "https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/version.json");
        assertEquals("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/version.json", next.toString());
    }

    @Test
    void establishesDelegatedOriginFromGithubResponse() throws IOException {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        URI next = policy.nextUri(GITHUB_PAYLOAD, "https://objects.githubusercontent.com/abc?sig=xyz");
        assertEquals("https://objects.githubusercontent.com/abc?sig=xyz", next.toString());
    }

    @Test
    void allowsRedirectWithinDelegatedOrigin() throws IOException {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        URI delegated = policy.nextUri(GITHUB_PAYLOAD, "https://objects.githubusercontent.com/abc?sig=xyz");
        URI next = policy.nextUri(delegated, "https://objects.githubusercontent.com/def?sig=uvw");
        assertEquals("https://objects.githubusercontent.com/def?sig=uvw", next.toString());
    }

    @Test
    void rejectsSecondUnrelatedOrigin() throws IOException {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        URI delegated = policy.nextUri(GITHUB_PAYLOAD, "https://objects.githubusercontent.com/abc");
        assertThrows(IOException.class, () -> policy.nextUri(delegated, "https://evil.example.com/payload"));
    }

    @Test
    void allowsReturnToTrustedGithubPath() throws IOException {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        URI delegated = policy.nextUri(GITHUB_PAYLOAD, "https://objects.githubusercontent.com/abc");
        URI next = policy.nextUri(delegated,
                "https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar");
        assertEquals("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar", next.toString());
    }

    @Test
    void rejectsHttpDowngrade() {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        assertThrows(IOException.class, () -> policy.nextUri(GITHUB_PAYLOAD,
                "http://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar"));
    }

    @Test
    void rejectsNonDefaultPort() {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        assertThrows(IOException.class, () -> policy.nextUri(GITHUB_PAYLOAD,
                "https://github.com:8443/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar"));
    }

    @Test
    void rejectsUserinfo() {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        assertThrows(IOException.class, () -> policy.nextUri(GITHUB_PAYLOAD,
                "https://user:pass@objects.githubusercontent.com/abc"));
    }

    @Test
    void rejectsIpLiteral() {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        assertThrows(IOException.class, () -> policy.nextUri(GITHUB_PAYLOAD, "https://127.0.0.1/payload"));
    }

    @Test
    void rejectsIpv6Literal() {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        assertThrows(IOException.class, () -> policy.nextUri(GITHUB_PAYLOAD, "https://[::1]/payload"));
    }

    @Test
    void rejectsTooManyRedirects() throws IOException {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        URI delegated = policy.nextUri(GITHUB_PAYLOAD, "https://objects.githubusercontent.com/0");
        for (int i = 1; i < 5; i++) {
            delegated = policy.nextUri(delegated, "https://objects.githubusercontent.com/" + i);
        }
        URI finalDelegated = delegated;
        assertThrows(IOException.class, () -> policy.nextUri(finalDelegated,
                "https://objects.githubusercontent.com/5"));
    }

    @Test
    void rejectsMissingLocation() {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        assertThrows(IOException.class, () -> policy.nextUri(GITHUB_PAYLOAD, null));
    }

    @Test
    void rejectsQueryOnTrustedGithubPath() throws IOException {
        DelegatedRedirectPolicy policy = new DelegatedRedirectPolicy(GITHUB_PAYLOAD);
        assertThrows(IOException.class, () -> policy.nextUri(GITHUB_PAYLOAD,
                "https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar?x=1"));
    }
}
