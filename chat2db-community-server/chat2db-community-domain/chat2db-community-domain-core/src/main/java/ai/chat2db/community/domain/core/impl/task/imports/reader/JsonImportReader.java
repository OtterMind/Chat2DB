package ai.chat2db.community.domain.core.impl.task.imports.reader;

import ai.chat2db.community.domain.api.model.task.JsonOptions;
import ai.chat2db.community.tools.exception.BusinessException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** Streams records and uses identical field paths in preview and execution. */
public final class JsonImportReader {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public static void read(File file, JsonOptions settings, int limit,
            BiConsumer<Map<String, ImportCell>, Integer> consumer, Runnable checkCancelled) {
        read(file, settings, limit, consumer, checkCancelled, false);
    }

    /**
     * Reads records while optionally scanning the complete document after the preview limit.
     * The caller can use the callback to retain only the bounded preview and still discover
     * fields that occur in later records.
     */
    public static void read(File file, JsonOptions settings, int limit,
            BiConsumer<Map<String, ImportCell>, Integer> consumer, Runnable checkCancelled,
            boolean scanRemaining) {
        JsonOptions options = settings.validate();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), Charset.forName(options.getEncoding()))) {
            reader.mark(1);
            if (reader.read() != '\uFEFF') reader.reset();
            if ("LINES".equals(options.getStructure())) {
                String line;
                int number = 0;
                int count = 0;
                while ((line = reader.readLine()) != null) {
                    checkCancelled.run();
                    number++;
                    if (line.isBlank()) continue;
                    try (JsonParser parser = MAPPER.createParser(line)) {
                        JsonNode record = MAPPER.readTree(parser);
                        if (parser.nextToken() != null) throw new BusinessException("import.preview.jsonInvalidLines");
                        consumer.accept(fields(record, options), number);
                        count++;
                        if (!scanRemaining && count >= limit) {
                            break;
                        }
                    } catch (JsonProcessingException e) {
                        throw new BusinessException("import.preview.jsonInvalidLine", new Object[]{number}, e);
                    }
                }
                return;
            }
            try (JsonParser parser = MAPPER.createParser(reader)) {
                parser.nextToken();
                List<String> path = new ArrayList<>();
                var matcher = Pattern.compile("\\.([^.\\[\\]]+)|\\[([0-9]+)\\]").matcher(options.getDataPath().substring(1));
                while (matcher.find()) path.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
                if (!seek(parser, path, 0, checkCancelled)) {
                    throw new BusinessException("import.preview.jsonPathNotFound", new Object[]{options.getDataPath()});
                }
                if ("OBJECT".equals(options.getStructure())) {
                    consumer.accept(fields(MAPPER.readTree(parser), options), 1);
                } else {
                    if (parser.currentToken() != JsonToken.START_ARRAY) throw new BusinessException("import.preview.jsonExpectedArray", new Object[]{options.getDataPath()});
                    int count = 0;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        checkCancelled.run();
                        if (parser.currentToken() == null) throw new BusinessException("import.preview.jsonInvalidDocument");
                        consumer.accept(fields(MAPPER.readTree(parser), options), ++count);
                        if (!scanRemaining && count >= limit) return;
                    }
                    if (count == 0) throw new BusinessException("import.preview.emptyFile");
                }
                // The preview scans the whole document for field discovery, so both callers can
                // reject a trailing document here; a bounded read stops before this point.
                if (scanRemaining || limit == Integer.MAX_VALUE) {
                    while (parser.nextToken() != null) {
                        checkCancelled.run();
                        var token = parser.currentToken();
                        var context = parser.getParsingContext();
                        if (token.isStructStart() && context.getParent().inRoot()
                                || context.inRoot() && token != JsonToken.END_OBJECT && token != JsonToken.END_ARRAY) {
                            throw new BusinessException("import.preview.jsonInvalidDocument");
                        }
                    }
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (CharacterCodingException e) {
            throw new BusinessException("import.preview.jsonEncodingMismatch", new Object[]{options.getEncoding()}, e);
        } catch (JsonProcessingException e) {
            var location = e.getLocation();
            if (location == null) throw new BusinessException("import.preview.jsonInvalidDocument", null, e);
            throw new BusinessException("import.preview.jsonInvalidSyntax",
                    new Object[]{location.getLineNr(), location.getColumnNr()}, e);
        } catch (IOException e) {
            throw new BusinessException("import.preview.fileUnreadable", null, e);
        }
    }

    private static boolean seek(JsonParser parser, List<String> path, int depth, Runnable checkCancelled) throws IOException {
        if (depth == path.size()) return true;
        String segment = path.get(depth);
        if (parser.currentToken() == JsonToken.START_OBJECT) {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                checkCancelled.run();
                if (parser.currentToken() != JsonToken.FIELD_NAME) return false;
                String name = parser.currentName();
                parser.nextToken();
                if (name.equals(segment)) return seek(parser, path, depth + 1, checkCancelled);
                parser.skipChildren();
            }
        } else if (parser.currentToken() == JsonToken.START_ARRAY && segment.matches("[0-9]+")) {
            int index = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
                checkCancelled.run();
                if (Integer.toString(index++).equals(segment)) return seek(parser, path, depth + 1, checkCancelled);
                parser.skipChildren();
            }
        }
        return false;
    }

    private static Map<String, ImportCell> fields(JsonNode node, JsonOptions options) {
        if (node == null || !node.isObject()) throw new BusinessException("import.preview.jsonExpectedObject", new Object[]{options.getDataPath()});
        Map<String, ImportCell> fields = new LinkedHashMap<>();
        flatten(node, "", fields, options);
        return fields;
    }

    private static void flatten(JsonNode object, String prefix, Map<String, ImportCell> fields, JsonOptions options) {
        object.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            String name = key.matches("[^.\\[\\]']+") ? (prefix.isEmpty() ? key : prefix + "." + key)
                    : prefix + "['" + key.replace("\\", "\\\\").replace("'", "\\'") + "']";
            JsonNode node = entry.getValue();
            Object value = node.isNull() ? null : node.isTextual() ? node.textValue()
                    : node.isNumber() ? node.numberValue() : node.isBoolean() ? node.booleanValue() : node.toString();
            if (options.getEmptyAsNull() && "".equals(value)) value = null;
            fields.put(name, new ImportCell(value, node.isTextual()));
            if (node.isObject()) flatten(node, name, fields, options);
        });
    }
}
