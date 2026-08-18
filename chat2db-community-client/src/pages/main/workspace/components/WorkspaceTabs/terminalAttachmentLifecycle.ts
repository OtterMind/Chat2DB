interface TerminalAttachmentParams {
  sessionId: string;
  consumerId: string;
}

interface TerminalAttachmentLifecycleOptions {
  attach: (params: TerminalAttachmentParams) => Promise<unknown>;
  detach: (params: TerminalAttachmentParams) => Promise<unknown>;
  createConsumerId?: (sessionId: string) => string;
  scheduleDetach?: (callback: () => void) => unknown;
  cancelScheduledDetach?: (handle: unknown) => void;
}

interface ScheduledDetach {
  handle: unknown;
}

interface TerminalAttachmentEntry extends TerminalAttachmentParams {
  attachPromise: Promise<unknown>;
  leaseCount: number;
  scheduledDetach: ScheduledDetach | null;
}

export interface TerminalAttachmentLease {
  attached: Promise<unknown>;
  release: () => void;
}

const defaultCreateConsumerId = (sessionId: string) =>
  `${sessionId}:${Date.now()}:${Math.random()
    .toString(36)
    .slice(2)}`;

const defaultScheduleDetach = (callback: () => void) => setTimeout(callback, 0);

const defaultCancelScheduledDetach = (handle: unknown) => {
  clearTimeout(handle as ReturnType<typeof setTimeout>);
};

function invokeAsPromise(callback: () => Promise<unknown>): Promise<unknown> {
  try {
    return Promise.resolve(callback());
  } catch (error) {
    return Promise.reject(error);
  }
}

export function createTerminalAttachmentLifecycle({
  attach,
  detach,
  createConsumerId = defaultCreateConsumerId,
  scheduleDetach = defaultScheduleDetach,
  cancelScheduledDetach = defaultCancelScheduledDetach,
}: TerminalAttachmentLifecycleOptions) {
  const entries = new Map<string, TerminalAttachmentEntry>();

  const acquire = (sessionId: string): TerminalAttachmentLease => {
    let entry = entries.get(sessionId);
    if (!entry) {
      const consumerId = createConsumerId(sessionId);
      entry = {
        sessionId,
        consumerId,
        attachPromise: invokeAsPromise(() => attach({ sessionId, consumerId })),
        leaseCount: 0,
        scheduledDetach: null,
      };
      entries.set(sessionId, entry);
    }

    if (entry.scheduledDetach) {
      cancelScheduledDetach(entry.scheduledDetach.handle);
      entry.scheduledDetach = null;
    }
    entry.leaseCount += 1;

    let released = false;
    return {
      attached: entry.attachPromise,
      release: () => {
        if (released) {
          return;
        }
        released = true;
        entry.leaseCount -= 1;
        if (entry.leaseCount > 0 || entry.scheduledDetach) {
          return;
        }

        // Pane migration unmounts and remounts the same session within one React effect flush.
        const scheduledDetach: ScheduledDetach = { handle: undefined };
        entry.scheduledDetach = scheduledDetach;
        scheduledDetach.handle = scheduleDetach(() => {
          if (entry.scheduledDetach !== scheduledDetach || entry.leaseCount > 0) {
            return;
          }
          entry.scheduledDetach = null;
          entries.delete(sessionId);
          void entry.attachPromise
            .catch(() => undefined)
            .then(() => detach({ sessionId, consumerId: entry.consumerId }))
            .catch(() => undefined);
        });
      },
    };
  };

  return { acquire };
}
