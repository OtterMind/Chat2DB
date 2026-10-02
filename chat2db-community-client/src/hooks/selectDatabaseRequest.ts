import {
  beginLatestRequest,
  invalidateLatestRequest,
  isLatestRequest,
  type RequestGenerationRef,
} from '@/utils/latestRequest';

export type SelectionRequestLevel = 'dataSource' | 'database' | 'schema';

export async function runLatestSelectionRequest<T>(
  generationRef: RequestGenerationRef,
  request: () => Promise<T>,
  onSuccess: (value: T) => void,
  onFailure: (error: unknown) => void,
) {
  const generation = beginLatestRequest(generationRef);
  try {
    const value = await request();
    if (!isLatestRequest(generationRef, generation)) {
      return false;
    }
    onSuccess(value);
    return true;
  } catch (error) {
    if (!isLatestRequest(generationRef, generation)) {
      return false;
    }
    onFailure(error);
    return true;
  }
}

export function createSelectDatabaseRequestCoordinator() {
  const generations: Record<SelectionRequestLevel, RequestGenerationRef> = {
    dataSource: { current: 0 },
    database: { current: 0 },
    schema: { current: 0 },
  };

  return {
    run<T>(
      level: SelectionRequestLevel,
      request: () => Promise<T>,
      onSuccess: (value: T) => void,
      onFailure: (error: unknown) => void,
    ) {
      return runLatestSelectionRequest(generations[level], request, onSuccess, onFailure);
    },
    invalidate(level: SelectionRequestLevel) {
      invalidateLatestRequest(generations[level]);
    },
    invalidateAll() {
      (Object.keys(generations) as SelectionRequestLevel[]).forEach((level) => {
        invalidateLatestRequest(generations[level]);
      });
    },
  };
}
