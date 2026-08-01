export interface SubscriptionStartupRecoveryOptions {
  shouldRetry: () => boolean;
  waitForRetry?: () => Promise<void>;
  maxRecoveryRetries?: number;
}

const waitForStartupRecovery = () => new Promise<void>((resolve) => window.setTimeout(resolve, 1000));

export async function hydrateSubscriptionModelOptions(
  refreshSurface: () => Promise<void>,
  loadModelOptions: () => Promise<void>,
  isActive: () => boolean,
  recovery?: SubscriptionStartupRecoveryOptions,
): Promise<void> {
  const maxRecoveryRetries = Math.max(0, recovery?.maxRecoveryRetries ?? 4);
  const waitForRetry = recovery?.waitForRetry ?? waitForStartupRecovery;

  for (let attempt = 0; attempt <= maxRecoveryRetries; attempt += 1) {
    await refreshSurface();
    if (!isActive()) {
      return;
    }
    await loadModelOptions();
    if (!isActive() || !recovery?.shouldRetry() || attempt === maxRecoveryRetries) {
      return;
    }
    await waitForRetry();
    if (!isActive()) {
      return;
    }
  }
}
