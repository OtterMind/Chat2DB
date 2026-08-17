import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { resolveRuntimeEditionCapabilities } from '@/edition-ui/runtimeCapabilities';
import { useUserStore } from '@/store/user';
import { useMemo } from 'react';

export function getRuntimeEditionCapabilities() {
  return resolveRuntimeEditionCapabilities(
    runtimeEditionConfig,
    Boolean(useUserStore.getState().networkAbandoned),
  );
}

export default function useRuntimeEditionCapabilities() {
  const networkAbandoned = useUserStore((state) => Boolean(state.networkAbandoned));
  return useMemo(
    () => resolveRuntimeEditionCapabilities(runtimeEditionConfig, networkAbandoned),
    [networkAbandoned],
  );
}
