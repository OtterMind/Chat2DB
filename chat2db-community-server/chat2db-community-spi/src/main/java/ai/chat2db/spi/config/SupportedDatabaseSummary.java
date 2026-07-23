package ai.chat2db.spi.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Frontend-facing summary of one registered database type, derived from the
 * plugin registry so clients can render supported databases dynamically
 * instead of hardcoding the list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportedDatabaseSummary {

    private String dbType;

    private String name;

    private boolean supportDatabase;

    private boolean supportSchema;

    private String sqlDialect;

    private String jdbcDriverClass;

    private String urlSample;
}
