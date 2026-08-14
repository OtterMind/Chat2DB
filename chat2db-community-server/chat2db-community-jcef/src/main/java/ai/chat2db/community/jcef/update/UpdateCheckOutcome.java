package ai.chat2db.community.jcef.update;

/** Immutable output of the remote discovery step, before JCEF result adaptation. */
record UpdateCheckOutcome(boolean needsUpdate, String releaseNotes, AvailableSnapshot snapshot, String releasePageUrl) {
}
