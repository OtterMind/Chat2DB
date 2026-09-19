package ai.chat2db.community.domain.core.impl.task.imports.reader;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A source cell keeps native dates/numbers separate from formatted text. */
public record ImportCell(Object value, boolean text) {
    public String display() {
        if (value instanceof BigDecimal number) return number.stripTrailingZeros().toPlainString();
        if (value instanceof LocalDateTime dateTime) return dateTime.toString().replace('T', ' ');
        return value == null ? null : value.toString();
    }
}
