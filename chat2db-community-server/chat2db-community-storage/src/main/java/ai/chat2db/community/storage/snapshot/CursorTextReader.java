package ai.chat2db.community.storage.snapshot;

import ai.chat2db.community.tools.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Encodes a leased text value while the client reads it. Only the bytes a slice still needs are kept, so paging through
 * a value whose size is independent of the heap costs the requested window; a value nobody opens is never transferred
 * at all.
 */
@Slf4j
final class CursorTextReader {

    private static final int COPY_BUFFER_BYTES = 8192;

    /**
     * Longest UTF-8 encoding of one character.
     */
    private static final int UTF8_MAX_BYTES_PER_CHARACTER = 4;

    private final Clob clob;
    /**
     * Encoded bytes the caller has not taken yet: the live window is {@code buffer[start, end)} and its first byte
     * sits at {@link #windowStart} in the value. Dropping a handed out prefix only moves {@code start}, so paging
     * never moves the bytes that are still to come.
     */
    private byte[] buffer = new byte[COPY_BUFFER_BYTES];
    private int start;
    private int end;
    private long windowStart;
    private Reader reader;
    private char pendingHighSurrogate;
    private boolean complete;

    CursorTextReader(Clob clob) {
        this.clob = clob;
    }

    synchronized byte[] slice(long offset, int limit) {
        long wanted = Math.max(0L, offset);
        if (wanted < windowStart) {
            // A window that was already handed out is read again from the start of the value
            restart();
        }
        // One complete character beyond the window, so the slice can end on a boundary
        fillTo(wanted + limit + UTF8_MAX_BYTES_PER_CHARACTER);
        discardBefore(wanted);
        int from = (int) Math.min(available(), Math.max(0L, wanted - windowStart));
        int to = Math.min(available(), from + limit);
        // A slice always ends on a character boundary, so callers can concatenate the bytes and decode once
        while (to > from && to < available() && (buffer[start + to] & 0xC0) == 0x80) {
            to--;
        }
        byte[] slice = Arrays.copyOfRange(buffer, start + from, start + to);
        discardBefore(windowStart + to);
        return slice;
    }

    synchronized boolean isComplete() {
        return complete;
    }

    synchronized long knownSizeBytes() {
        return windowStart + available();
    }

    private int available() {
        return end - start;
    }

    /**
     * Drops the bytes before an offset that was already handed out. Everything before the first byte a caller can
     * still ask for is garbage once the value is longer than the windows that were served.
     */
    private void discardBefore(long offset) {
        long droppable = offset - windowStart;
        if (droppable <= 0) {
            return;
        }
        int drop = (int) Math.min(droppable, available());
        if (drop <= 0) {
            return;
        }
        start += drop;
        windowStart += drop;
        if (start >= buffer.length / 2) {
            compact();
        }
    }

    /**
     * Moves the live window to the front so the buffer can be reused for the bytes still to come.
     */
    private void compact() {
        if (start == 0) {
            return;
        }
        int available = available();
        System.arraycopy(buffer, start, buffer, 0, available);
        start = 0;
        end = available;
    }

    /**
     * Starts over from the first character. Only a caller that asks for an offset this reader already dropped pays
     * for it, and it costs a pass over the value instead of holding the whole of it.
     */
    private void restart() {
        closeReader();
        reader = null;
        buffer = new byte[COPY_BUFFER_BYTES];
        start = 0;
        end = 0;
        windowStart = 0L;
        pendingHighSurrogate = 0;
        complete = false;
    }

    private void closeReader() {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException e) {
            log.warn("Failed to close the cursor stream of a leased value: {}", e.getMessage());
        }
    }

    private void fillTo(long targetBytes) {
        if (complete || knownSizeBytes() >= targetBytes) {
            return;
        }
        try {
            if (reader == null) {
                reader = clob.getCharacterStream();
            }
            char[] characters = new char[COPY_BUFFER_BYTES];
            while (knownSizeBytes() < targetBytes) {
                int read = reader.read(characters);
                if (read < 0) {
                    // A high surrogate the value ends on is emitted by its own read, like a one pass read would
                    if (pendingHighSurrogate != 0) {
                        appendCharacters(String.valueOf(pendingHighSurrogate));
                        pendingHighSurrogate = 0;
                    }
                    complete = true;
                    return;
                }
                if (read > 0) {
                    append(characters, read);
                }
            }
        } catch (IOException | SQLException e) {
            throw new BusinessException("largeCellValue.snapshotReadFailed", new Object[]{"leased value"}, e);
        }
    }

    /**
     * Encodes one chunk of characters, holding back a surrogate split across two reads so it is never turned into a
     * replacement character.
     */
    private void append(char[] characters, int length) {
        String text = new String(characters, 0, length);
        if (pendingHighSurrogate != 0) {
            text = pendingHighSurrogate + text;
            pendingHighSurrogate = 0;
        }
        if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
            pendingHighSurrogate = text.charAt(text.length() - 1);
            text = text.substring(0, text.length() - 1);
        }
        appendCharacters(text);
    }

    private void appendCharacters(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            return;
        }
        makeRoomFor(bytes.length);
        System.arraycopy(bytes, 0, buffer, end, bytes.length);
        end += bytes.length;
    }

    private void makeRoomFor(int length) {
        if (end + length <= buffer.length) {
            return;
        }
        compact();
        if (end + length > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, end + length));
        }
    }
}
