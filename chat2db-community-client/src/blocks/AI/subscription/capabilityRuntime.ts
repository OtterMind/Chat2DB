import type { AiSubscriptionCapability } from '@/typings/aiSubscription';
import { isCommunityEnv, isDesktop } from '@/utils/env';
import type { SubscriptionSurfaceSignals } from './capability';

/** Live Community packaged-JCEF probe used by components and services. */
export function readSubscriptionSurfaceSignals(
  backendCapability?: AiSubscriptionCapability | null,
): SubscriptionSurfaceSignals {
  return {
    communityRuntime: isCommunityEnv,
    packagedJcefDesktop: isDesktop,
    backendCapability: backendCapability ?? null,
  };
}
