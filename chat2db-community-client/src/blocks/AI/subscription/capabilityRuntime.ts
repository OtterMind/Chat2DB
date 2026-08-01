import type { AiSubscriptionCapability } from '@/typings/aiSubscription';
import { isCommunityEnv, isPackagedJcefDesktop } from '@/utils/env';
import type { SubscriptionSurfaceSignals } from './capability';

/** Live Community packaged-JCEF probe used by components and services. */
export function readSubscriptionSurfaceSignals(
  backendCapability?: AiSubscriptionCapability | null,
): SubscriptionSurfaceSignals {
  return {
    communityRuntime: isCommunityEnv,
    // Always re-read the bridge — never trust a boot-time isDesktop snapshot.
    packagedJcefDesktop: isPackagedJcefDesktop(),
    backendCapability: backendCapability ?? null,
  };
}
