package ai.chat2db.community.updater.v2.telemetry;

/** Receives assembled usage reports; split out so the reporter can be replaced in tests. */
@FunctionalInterface
public interface TelemetrySink {

    void report(UsageTelemetryReporter.Report report);
}
