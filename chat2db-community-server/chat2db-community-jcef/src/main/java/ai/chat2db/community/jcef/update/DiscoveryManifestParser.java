package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Parses the lightweight update-discovery manifest with an identical type contract for every source. */
final class DiscoveryManifestParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DiscoveryManifestParser() {
    }

    static DiscoveryManifest parse(byte[] bytes) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(bytes);
        if (root == null || !root.isObject()) {
            throw new IOException("Update discovery manifest must be a JSON object");
        }
        return new DiscoveryManifest(
                requiredStringOrNull(root, "version"),
                optionalString(root, "releaseNotes"),
                optionalString(root, "releasePageUrl"),
                optionalBoolean(root, "forceUpdate"),
                optionalString(root, "metadataSha256"));
    }

    private static String requiredStringOrNull(JsonNode root, String field) throws IOException {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IOException("Update discovery manifest field " + field + " must be a string or null");
        }
        return value.textValue();
    }

    private static String optionalString(JsonNode root, String field) throws IOException {
        return requiredStringOrNull(root, field);
    }

    private static Boolean optionalBoolean(JsonNode root, String field) throws IOException {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new IOException("Update discovery manifest field " + field + " must be a boolean or null");
        }
        return value.booleanValue();
    }

    record DiscoveryManifest(String version, String releaseNotes, String releasePageUrl,
                             Boolean forceUpdate, String metadataSha256) {
    }
}
