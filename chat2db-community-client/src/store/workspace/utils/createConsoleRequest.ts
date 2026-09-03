export interface CreateConsoleRequestDependencies<TTab> {
  create: () => Promise<number>;
  getTabs: () => readonly TTab[] | null;
  buildTab: (consoleId: number) => TTab;
  setTabs: (tabs: TTab[]) => void;
  setActive: (consoleId: number) => void;
  begin: () => void;
  finish: () => void;
}

export function addPendingConsoleRequest(requestIds: readonly string[], requestId: string) {
  return requestIds.includes(requestId) ? [...requestIds] : [...requestIds, requestId];
}

export function removePendingConsoleRequest(requestIds: readonly string[], requestId: string) {
  return requestIds.filter((currentRequestId) => currentRequestId !== requestId);
}

export async function runCreateConsoleRequest<TTab>(dependencies: CreateConsoleRequestDependencies<TTab>) {
  try {
    dependencies.begin();
    const consoleId = await dependencies.create();
    dependencies.setTabs([...(dependencies.getTabs() || []), dependencies.buildTab(consoleId)]);
    dependencies.setActive(consoleId);
    return consoleId;
  } finally {
    dependencies.finish();
  }
}
