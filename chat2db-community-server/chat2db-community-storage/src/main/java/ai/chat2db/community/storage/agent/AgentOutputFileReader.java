package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.output.AgentOutputRead;
import ai.chat2db.community.domain.api.model.agent.output.AgentOutputSearch;
import ai.chat2db.community.tools.exception.storage.StorageException;
import com.google.re2j.Pattern;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded UTF-8 access shared by managed outputs and separately authorized native file tools. */
final class AgentOutputFileReader {
    static final int RESPONSE_BYTES = 16 * 1024;
    private static final int SEARCH_SCAN_BYTES = 4 * 1024 * 1024;
    private static final int SEARCH_OVERLAP_BYTES = 4096;

    private AgentOutputFileReader() { }

    static AgentOutputRead read(Path path, String cursor, Integer offset, Integer limit) {
        int lines = clamp(limit, 200, 2000);
        Position position = Position.parse(cursor);
        try (SeekableByteChannel channel = channel(path)) {
            if (cursor == null && offset != null) position = linePosition(path, Math.max(1, offset));
            if (position.bytes > channel.size()) throw new IllegalArgumentException("Output cursor is past the end of the file");
            Chunk chunk = chunk(channel, position.bytes, RESPONSE_BYTES);
            int end = chunk.bytes.length;
            int seen = 0;
            for (int i = 0; i < end; i++) {
                if (chunk.bytes[i] == '\n' && ++seen == lines) { end = i + 1; break; }
            }
            end = Math.min(end, jsonBudgetEnd(chunk.bytes, RESPONSE_BYTES - 512));
            byte[] bytes = Arrays.copyOf(chunk.bytes, end);
            long newlines = countLines(bytes);
            long next = position.bytes + end;
            boolean more = next < channel.size();
            boolean partial = more && end > 0 && bytes[end - 1] != '\n';
            long endLine = position.line + newlines - (end > 0 && bytes[end - 1] == '\n' ? 1 : 0);
            return new AgentOutputRead(new String(bytes, StandardCharsets.UTF_8),
                    more ? new Position(next, position.line + newlines).encode() : null, more,
                    position.line, Math.max(position.line, endLine), partial);
        } catch (IOException exception) {
            throw new StorageException("Could not read output file", exception);
        }
    }

    static AgentOutputSearch search(Path path, String expression, boolean literal, boolean ignoreCase,
            String cursor, Integer limit) {
        if (expression == null || expression.isEmpty() || expression.length() > 512) {
            throw new IllegalArgumentException("Search pattern must contain 1 to 512 characters");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(literal ? Pattern.quote(expression) : expression,
                    ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (com.google.re2j.PatternSyntaxException exception) {
            throw new IllegalArgumentException("Invalid search pattern: use RE2 syntax without lookaround or backreferences");
        }
        int maxMatches = clamp(limit, 100, 100);
        Position position = Position.parse(cursor);
        List<AgentOutputSearch.Match> matches = new ArrayList<>();
        int responseBytes = 0;
        long scanned = 0;
        boolean longLine = false;
        try (SeekableByteChannel channel = channel(path)) {
            if (position.bytes > channel.size()) throw new IllegalArgumentException("Output cursor is past the end of the file");
            searchLoop:
            while (position.bytes < channel.size() && scanned < SEARCH_SCAN_BYTES) {
                long chunkStart = position.bytes;
                byte[] bytes = chunk(channel, chunkStart, RESPONSE_BYTES).bytes;
                byte[] prefix = precedingLineTail(channel, chunkStart);
                int begin = 0;
                while (begin < bytes.length) {
                    long originalStart = chunkStart + begin;
                    int end = begin;
                    while (end < bytes.length && bytes[end] != '\n') end++;
                    boolean newline = end < bytes.length;
                    int consumed = end - begin + (newline ? 1 : 0);
                    boolean partial = !newline && originalStart + consumed < channel.size();
                    byte[] searchBytes = new byte[prefix.length + end - begin];
                    System.arraycopy(prefix, 0, searchBytes, 0, prefix.length);
                    System.arraycopy(bytes, begin, searchBytes, prefix.length, end - begin);
                    String text = new String(searchBytes, StandardCharsets.UTF_8);
                    int prefixCharacters = new String(prefix, StandardCharsets.UTF_8).length();
                    var matcher = pattern.matcher(text);
                    boolean found = false;
                    while (matcher.find()) {
                        if (matcher.end() <= prefixCharacters && prefixCharacters > 0) continue;
                        found = true;
                        break;
                    }
                    if (found) {
                        int snippetStart = Math.max(0, matcher.start() - 160);
                        if (snippetStart > 0 && Character.isLowSurrogate(text.charAt(snippetStart))) snippetStart++;
                        int snippetEnd = Math.min(text.length(), Math.max(matcher.end(), matcher.start() + 1200));
                        if (snippetEnd < text.length() && Character.isLowSurrogate(text.charAt(snippetEnd))) snippetEnd--;
                        String snippet = truncateUtf8(text.substring(snippetStart, snippetEnd), 2048);
                        int cost = jsonBytes(snippet.getBytes(StandardCharsets.UTF_8)) + 100;
                        if (responseBytes + cost > RESPONSE_BYTES - 512 || matches.size() >= maxMatches) break searchLoop;
                        long matchOffset = originalStart - prefix.length
                                + text.substring(0, matcher.start()).getBytes(StandardCharsets.UTF_8).length;
                        matches.add(new AgentOutputSearch.Match(position.line, snippet, matchOffset));
                        responseBytes += cost;
                    }
                    if (consumed == 0) break searchLoop;
                    scanned += consumed;
                    position = new Position(originalStart + consumed, position.line + (newline ? 1 : 0));
                    longLine |= partial || prefix.length > 0;
                    if (matches.size() >= maxMatches) break searchLoop;
                    begin += consumed;
                    prefix = new byte[0];
                }
            }
            boolean more = position.bytes < channel.size();
            return new AgentOutputSearch(List.copyOf(matches), more ? position.encode() : null, more,
                    longLine && !literal ? "Very long lines are searched in bounded windows with 4 KiB overlap; use read for expressions spanning longer ranges" : null);
        } catch (IOException exception) {
            throw new StorageException("Could not search output file", exception);
        }
    }

    private static byte[] precedingLineTail(SeekableByteChannel channel, long position) throws IOException {
        if (position == 0) return new byte[0];
        long start = Math.max(0, position - SEARCH_OVERLAP_BYTES);
        channel.position(start);
        ByteBuffer buffer = ByteBuffer.allocate((int) (position - start));
        while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
        byte[] bytes = Arrays.copyOf(buffer.array(), buffer.position());
        int begin = bytes.length;
        while (begin > 0 && bytes[begin - 1] != '\n') begin--;
        while (begin < bytes.length && continuation(bytes[begin])) begin++;
        return Arrays.copyOfRange(bytes, begin, bytes.length);
    }

    private static SeekableByteChannel channel(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new StorageException("Output file does not exist");
        return Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    private static Position linePosition(Path path, int offset) throws IOException {
        long bytes = 0;
        long line = 1;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))) {
            int value;
            while (line < offset && (value = input.read()) != -1) {
                bytes++;
                if (value == '\n') line++;
            }
        }
        return new Position(bytes, line);
    }

    private static Chunk chunk(SeekableByteChannel channel, long position, int limit) throws IOException {
        channel.position(position);
        ByteBuffer buffer = ByteBuffer.allocate(limit + 4);
        while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
        int available = buffer.position();
        int end = Math.min(limit, available);
        byte[] bytes = buffer.array();
        if (available > 0 && continuation(bytes[0])) throw new IllegalArgumentException("Cursor must point to a UTF-8 character boundary");
        while (end < available && end > 0 && continuation(bytes[end])) end--;
        return new Chunk(Arrays.copyOf(bytes, end));
    }

    private static long countLines(byte[] bytes) {
        long count = 0;
        for (byte value : bytes) if (value == '\n') count++;
        return count;
    }

    private static boolean continuation(byte value) { return (value & 0xc0) == 0x80; }
    private static int clamp(Integer value, int fallback, int maximum) {
        if (value == null) return fallback;
        if (value < 1) throw new IllegalArgumentException("Limit must be positive");
        return Math.min(value, maximum);
    }

    private static String truncateUtf8(String value, int limit) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int end = Math.min(bytes.length, limit);
        while (end < bytes.length && end > 0 && continuation(bytes[end])) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static int jsonBudgetEnd(byte[] bytes, int budget) {
        int offset = 0;
        while (offset < bytes.length) {
            int value = bytes[offset] & 0xff;
            int width = value < 0x80 ? 1 : value < 0xe0 ? 2 : value < 0xf0 ? 3 : 4;
            int cost = width == 4 ? 12 : value < 0x20 ? 6 : value == '"' || value == '\\' ? 2 : width;
            if (cost > budget || offset + width > bytes.length) break;
            budget -= cost;
            offset += width;
        }
        return offset;
    }

    private static int jsonBytes(byte[] bytes) {
        int size = 0;
        for (int offset = 0; offset < bytes.length;) {
            int value = bytes[offset] & 0xff;
            int width = value < 0x80 ? 1 : value < 0xe0 ? 2 : value < 0xf0 ? 3 : 4;
            size += width == 4 ? 12 : value < 0x20 ? 6 : value == '"' || value == '\\' ? 2 : width;
            offset += width;
        }
        return size;
    }

    private record Chunk(byte[] bytes) { }
    private record Position(long bytes, long line) {
        String encode() { return Base64.getUrlEncoder().withoutPadding().encodeToString((bytes + ":" + line).getBytes(StandardCharsets.US_ASCII)); }
        static Position parse(String cursor) {
            if (cursor == null || cursor.isBlank()) return new Position(0, 1);
            try {
                if (cursor.length() > 128) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split(":");
                if (parts.length != 2) throw new IllegalArgumentException();
                long bytes = Long.parseLong(parts[0]);
                long line = Long.parseLong(parts[1]);
                if (bytes < 0 || line < 1) throw new IllegalArgumentException();
                return new Position(bytes, line);
            } catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid output cursor"); }
        }
    }
}
