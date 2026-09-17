package ai.chat2db.community.domain.core.impl.task.imports.reader;

/** A source cell keeps native dates/numbers separate from formatted text. */
public record ImportCell(Object value, boolean text) {
    public String display() {
        return value instanceof java.math.BigDecimal number ? number.stripTrailingZeros().toPlainString()
                : value == null ? null : value.toString();
    }
}
