type TimerHandle = ReturnType<typeof setInterval>;

export interface CountdownScheduler {
  setInterval: (callback: () => void, delay: number) => TimerHandle;
  clearInterval: (handle: TimerHandle) => void;
}

export interface CountdownRequest {
  isCurrent: () => boolean;
  startCountdown: (seconds?: number) => boolean;
  track<T>(promise: Promise<T>, onSuccess: (value: T) => void, onSettled: () => void): Promise<T>;
}

export interface VerificationCodeCountdownLifecycle {
  beginRequest: () => CountdownRequest;
  dispose: () => void;
}

const browserScheduler: CountdownScheduler = {
  setInterval: (callback, delay) => setInterval(callback, delay),
  clearInterval: (handle) => clearInterval(handle),
};

export function createVerificationCodeCountdownLifecycle(
  onCountdownChange: (countdown: number | null) => void,
  scheduler: CountdownScheduler = browserScheduler,
): VerificationCodeCountdownLifecycle {
  let disposed = false;
  let generation = 0;
  let activeTimer: TimerHandle | null = null;

  const clearOwnedTimer = (timer: TimerHandle) => {
    scheduler.clearInterval(timer);
    if (activeTimer === timer) {
      activeTimer = null;
    }
  };

  const clearActiveTimer = () => {
    if (activeTimer !== null) {
      clearOwnedTimer(activeTimer);
    }
  };

  const beginRequest = (): CountdownRequest => {
    const requestGeneration = ++generation;
    clearActiveTimer();

    const isCurrent = () => !disposed && generation === requestGeneration;
    const startCountdown = (seconds = 60) => {
      if (!isCurrent()) {
        return false;
      }

      clearActiveTimer();
      let countdown = seconds;
      onCountdownChange(countdown);

      const timer = scheduler.setInterval(() => {
        if (!isCurrent()) {
          clearOwnedTimer(timer);
          return;
        }

        countdown -= 1;
        onCountdownChange(countdown);
        if (countdown <= 0) {
          clearOwnedTimer(timer);
          onCountdownChange(null);
        }
      }, 1000);
      activeTimer = timer;
      return true;
    };

    return {
      isCurrent,
      startCountdown,
      track: (promise, onSuccess, onSettled) =>
        promise
          .then((value) => {
            if (isCurrent()) {
              onSuccess(value);
            }
            return value;
          })
          .finally(() => {
            if (isCurrent()) {
              onSettled();
            }
          }),
    };
  };

  return {
    beginRequest,
    dispose: () => {
      disposed = true;
      generation += 1;
      clearActiveTimer();
    },
  };
}
