package ai.chat2db.spi.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One exclusive lower bound of a keyset cursor: rows where {@code column} is strictly greater (or
 * smaller, for a descending key) than {@code valueLiteral}. The value is a dialect-formatted SQL
 * literal produced by the value processor, not a bind parameter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyBound {

    private String column;

    private String valueLiteral;

    private boolean ascending;
}
