import { createHttpAiSubscriptionClient } from './httpClient';
import { createMockAiSubscriptionClient } from './mockClient';
import type { AiSubscriptionClient } from './types';

export type { AiSubscriptionClient, StartConnectResult } from './types';
export { createHttpAiSubscriptionClient } from './httpClient';
export {
  createMockAiSubscriptionClient,
  createDefaultMockSubscriptionState,
  type MockSubscriptionState,
} from './mockClient';

let activeClient: AiSubscriptionClient | null = null;

/**
 * Injectable subscription backend client. Tests and Story-like UIs can swap a mock.
 * Default is the HTTP adapter; when backend routes are absent, callers should handle errors.
 */
export function getAiSubscriptionClient(): AiSubscriptionClient {
  if (!activeClient) {
    activeClient = createHttpAiSubscriptionClient();
  }
  return activeClient;
}

export function setAiSubscriptionClient(client: AiSubscriptionClient | null): void {
  activeClient = client;
}

export function useMockAiSubscriptionClientForTests(): ReturnType<typeof createMockAiSubscriptionClient> {
  const mock = createMockAiSubscriptionClient();
  activeClient = mock;
  return mock;
}
