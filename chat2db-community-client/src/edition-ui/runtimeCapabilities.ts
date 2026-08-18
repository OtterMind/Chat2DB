import type { RuntimeEditionConfig } from '@/constants/runtimeEdition';

type RuntimeCapabilityConfig = Pick<
  RuntimeEditionConfig,
  'aiDataCollection' | 'dashboardShare' | 'dashboardHostedAiGenerate'
>;

export interface RuntimeEditionCapabilities {
  aiDataCollection: boolean;
  dashboardShare: boolean;
  dashboardHostedAiGenerate: boolean;
}

export function resolveRuntimeEditionCapabilities(
  config: RuntimeCapabilityConfig,
  networkAbandoned: boolean,
): RuntimeEditionCapabilities {
  const gatewayFeaturesAvailable = !networkAbandoned;
  return {
    aiDataCollection: config.aiDataCollection && gatewayFeaturesAvailable,
    dashboardShare: config.dashboardShare && gatewayFeaturesAvailable,
    // Dashboard AI uses the shared local /api/v3/ai/chat/stream path and remains available offline.
    dashboardHostedAiGenerate: config.dashboardHostedAiGenerate,
  };
}

export function canShareDashboard(
  capabilities: Pick<RuntimeEditionCapabilities, 'dashboardShare'>,
  organizationType?: string | null,
) {
  return capabilities.dashboardShare && Boolean(organizationType) && organizationType !== 'PERSONAL';
}
