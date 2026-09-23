/**
 * Interval between scheduled update checks, in minutes. The list repeats, so a long running desktop
 * session checks every 30 minutes → 1 hour → 2 hours → 4 hours → 6 hours and then starts over.
 */
export const UPDATE_CHECK_INTERVAL_MINUTES = [30, 60, 120, 240, 360];

/** Delay before the given round (0-based) of the repeating schedule. */
export const updateCheckDelayMs = (round: number) => {
  const index = ((round % UPDATE_CHECK_INTERVAL_MINUTES.length) + UPDATE_CHECK_INTERVAL_MINUTES.length) %
    UPDATE_CHECK_INTERVAL_MINUTES.length;
  return UPDATE_CHECK_INTERVAL_MINUTES[index] * 60 * 1000;
};

/** Wall clock time the next scheduled check is due at. */
export const nextCheckDueAt = (now: number, round: number) => now + updateCheckDelayMs(round);

/**
 * A renderer that is hidden or napped by the OS does not run its timers, so a due check has to be
 * recognised when the window becomes visible again instead of waiting for the frozen timer.
 */
export const isCheckDue = (now: number, dueAt: number) => dueAt > 0 && now >= dueAt;

/** A version is announced only once per session, even though checks repeat. */
export const shouldNotifyVersion = (version: string | undefined, lastNotifiedVersion: string) =>
  Boolean(version) && version !== lastNotifiedVersion;

/**
 * Next value for the "already announced" marker. A change means the user should be notified, so a
 * repeated check for the same version - or a disabled reminder - leaves the marker untouched.
 */
export const nextNotifiedVersion = (
  remindMe: boolean | undefined,
  version: string | undefined,
  lastNotifiedVersion: string,
) => (remindMe && shouldNotifyVersion(version, lastNotifiedVersion) ? version ?? '' : lastNotifiedVersion);
