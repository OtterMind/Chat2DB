package ai.chat2db.spi.model.imports;

/** Read-only database facts captured immediately before import admission is decided. */
public record ImportResourceSnapshot(
        boolean connectionCapacityKnown,
        int maxConnections,
        int activeConnections,
        boolean replicationStatusKnown,
        boolean replica,
        Long replicationLagSeconds,
        boolean triggerStatusKnown,
        int triggerCount,
        boolean diskCapacityKnown,
        boolean diskCapacitySufficient,
        String evidence) {

    public static ImportResourceSnapshot unknown(String evidence) {
        return new ImportResourceSnapshot(false, 0, 0, false, false, null,
                false, 0, false, false, evidence);
    }
}
