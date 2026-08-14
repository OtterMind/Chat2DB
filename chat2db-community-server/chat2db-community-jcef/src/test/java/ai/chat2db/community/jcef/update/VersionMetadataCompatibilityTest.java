package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionMetadataCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesGitHubManifestWithNewFields() throws Exception {
        String json = """
                {
                  "version": "5.4.0",
                  "releaseNotes": "Known issue fixes",
                  "releasePageUrl": "https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0",
                  "forceUpdate": false,
                  "files": [],
                  "launchCommand": null
                }
                """;

        VersionMetadata metadata = objectMapper.readValue(json, VersionMetadata.class);

        assertEquals("5.4.0", metadata.getVersion());
        assertEquals("Known issue fixes", metadata.getReleaseNotes());
        assertEquals("https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0", metadata.getReleasePageUrl());
        assertEquals(Boolean.FALSE, metadata.getForceUpdate());
        assertNull(metadata.getLaunchCommand());
    }

    @Test
    void deserializesLegacyCdnManifestWithoutNewFields() throws Exception {
        String json = """
                {
                  "version": "5.3.1",
                  "releaseNotes": "Legacy notes",
                  "files": []
                }
                """;

        VersionMetadata metadata = objectMapper.readValue(json, VersionMetadata.class);

        assertEquals("5.3.1", metadata.getVersion());
        assertEquals("Legacy notes", metadata.getReleaseNotes());
        assertNull(metadata.getReleasePageUrl());
        assertNull(metadata.getForceUpdate());
    }

    @Test
    void serializesGitHubManifestWithNewFields() throws Exception {
        VersionMetadata metadata = new VersionMetadata();
        metadata.setVersion("5.4.0");
        metadata.setReleaseNotes("Notes");
        metadata.setReleasePageUrl("https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0");
        metadata.setForceUpdate(false);

        String json = objectMapper.writeValueAsString(metadata);

        assertTrue(json.contains("releasePageUrl"));
        assertTrue(json.contains("forceUpdate"));
    }

    @Test
    void serializesLegacyManifestWithoutNullFields() throws Exception {
        VersionMetadata metadata = new VersionMetadata();
        metadata.setVersion("5.3.1");
        metadata.setReleaseNotes("Notes");

        String json = objectMapper.writeValueAsString(metadata);

        assertFalse(json.contains("releasePageUrl"));
        assertFalse(json.contains("forceUpdate"));
    }
}
