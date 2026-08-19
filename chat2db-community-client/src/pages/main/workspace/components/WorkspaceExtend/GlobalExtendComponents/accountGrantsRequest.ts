import { beginLatestRequest, isLatestRequest, type RequestGenerationRef } from '@/utils/latestRequest';

type AccountGrantsLoader = () => Promise<string[] | undefined>;

export async function loadLatestAccountGrants(
  requestGenerationRef: RequestGenerationRef,
  loadGrants: AccountGrantsLoader,
  updateGrants: (grants: string[]) => void,
  settleLoading: () => void,
) {
  const requestGeneration = beginLatestRequest(requestGenerationRef);

  try {
    const grants = await loadGrants();
    if (isLatestRequest(requestGenerationRef, requestGeneration)) {
      updateGrants(grants || []);
    }
  } catch (_error) {
    if (isLatestRequest(requestGenerationRef, requestGeneration)) {
      updateGrants([]);
    }
  } finally {
    if (isLatestRequest(requestGenerationRef, requestGeneration)) {
      settleLoading();
    }
  }
}
