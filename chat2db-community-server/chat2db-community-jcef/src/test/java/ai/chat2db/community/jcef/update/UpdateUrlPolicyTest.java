package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateUrlPolicyTest {

    @Test
    void acceptsExactLatestManifestUrl() {
        assertDoesNotThrow(() -> UpdateUrlPolicy.validateLatestUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/latest/download/latest_version.json")));
    }

    @Test
    void rejectsHttpLatestUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validateLatestUrl(
                URI.create("http://github.com/OtterMind/Chat2DB/releases/latest/download/latest_version.json")));
    }

    @Test
    void rejectsUserinfoInLatestUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validateLatestUrl(
                URI.create("https://user:pass@github.com/OtterMind/Chat2DB/releases/latest/download/latest_version.json")));
    }

    @Test
    void rejectsNonDefaultPortInLatestUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validateLatestUrl(
                URI.create("https://github.com:8443/OtterMind/Chat2DB/releases/latest/download/latest_version.json")));
    }

    @Test
    void rejectsQueryInLatestUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validateLatestUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/latest/download/latest_version.json?x=1")));
    }

    @Test
    void rejectsFragmentInLatestUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validateLatestUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/latest/download/latest_version.json#x")));
    }

    @Test
    void rejectsDifferentRepoInLatestUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validateLatestUrl(
                URI.create("https://github.com/Evil/Chat2DB/releases/latest/download/latest_version.json")));
    }

    @Test
    void rejectsDifferentPathInLatestUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validateLatestUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/version.json")));
    }

    @Test
    void acceptsExactPayloadUrl() {
        assertDoesNotThrow(() -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar"),
                "5.4.0", "chat2db-community.jar"));
    }

    @Test
    void rejectsHttpPayloadUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("http://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar"),
                "5.4.0", "chat2db-community.jar"));
    }

    @Test
    void rejectsWrongVersionInPayloadUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.1/chat2db-community.jar"),
                "5.4.0", "chat2db-community.jar"));
    }

    @Test
    void rejectsWrongAssetInPayloadUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/lib.zip"),
                "5.4.0", "chat2db-community.jar"));
    }

    @Test
    void rejectsDotSegmentInPayloadUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/./chat2db-community.jar"),
                "5.4.0", "chat2db-community.jar"));
    }

    @Test
    void rejectsDotDotSegmentInPayloadUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/../version.json"),
                "5.4.0", "version.json"));
    }

    @Test
    void rejectsEncodedForwardSlashInPayloadUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db%2fcommunity.jar"),
                "5.4.0", "chat2db/community.jar"));
    }

    @Test
    void rejectsEncodedBackslashInPayloadUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db%5ccommunity.jar"),
                "5.4.0", "chat2db\\community.jar"));
    }

    @Test
    void rejectsQueryInPayloadUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar?x=1"),
                "5.4.0", "chat2db-community.jar"));
    }

    @Test
    void rejectsFragmentInPayloadUrl() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar#x"),
                "5.4.0", "chat2db-community.jar"));
    }

    @Test
    void rejectsIpLiteralHost() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://140.82.121.4/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar"),
                "5.4.0", "chat2db-community.jar"));
    }

    @Test
    void rejectsIpv6LiteralHost() {
        assertThrows(IOException.class, () -> UpdateUrlPolicy.validatePayloadUrl(
                URI.create("https://[::1]/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar"),
                "5.4.0", "chat2db-community.jar"));
    }
}
