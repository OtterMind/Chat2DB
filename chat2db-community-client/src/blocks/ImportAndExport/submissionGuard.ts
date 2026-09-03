export interface PendingSubmissionGuard {
  run<T>(action: () => Promise<T>): Promise<T> | undefined;
  isPending(): boolean;
}

export function createPendingSubmissionGuard(): PendingSubmissionGuard {
  let pending = false;
  return {
    run<T>(action: () => Promise<T>) {
      if (pending) return undefined;
      pending = true;
      try {
        return action().finally(() => {
          pending = false;
        });
      } catch (error) {
        pending = false;
        throw error;
      }
    },
    isPending() {
      return pending;
    },
  };
}
