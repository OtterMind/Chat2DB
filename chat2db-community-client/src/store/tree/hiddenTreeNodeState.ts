export class HiddenTreeNodeStateCoordinator<T> {
  private revision = 0;

  private initialized = false;

  private pendingInitialization: Promise<boolean> | null = null;

  private writeQueue: Promise<void> = Promise.resolve();

  initialize(read: () => Promise<T>, commit: (value: T) => void): Promise<boolean> {
    if (this.initialized) {
      return Promise.resolve(false);
    }

    if (this.pendingInitialization) {
      return this.pendingInitialization;
    }

    const revision = this.revision;
    const initialization = this.writeQueue
      .then(async () => {
        if (revision !== this.revision) {
          return false;
        }

        const value = await read();
        if (revision !== this.revision) {
          return false;
        }

        commit(value);
        this.initialized = true;
        return true;
      })
      .finally(() => {
        if (this.pendingInitialization === initialization) {
          this.pendingInitialization = null;
        }
      });

    this.pendingInitialization = initialization;
    return initialization;
  }

  write<R>(operation: () => Promise<R>): Promise<R> {
    const pendingInitialization = this.pendingInitialization;
    const prerequisite = pendingInitialization
      ? this.writeQueue.then(() => pendingInitialization, () => pendingInitialization)
      : this.writeQueue;
    const result = prerequisite.then(operation);
    this.writeQueue = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  reset(): void {
    this.invalidatePendingInitialization();
    this.initialized = false;
  }

  private invalidatePendingInitialization(): void {
    this.revision += 1;
    this.pendingInitialization = null;
  }
}

export interface HiddenTreeNodeChanges {
  add: (string | null | undefined)[];
  delete: (string | null | undefined)[];
}

export function applyHiddenTreeNodeChanges(
  currentIds: string[],
  changedKeys?: HiddenTreeNodeChanges,
): string[] {
  const nextIds = new Set(currentIds);
  if (!changedKeys) {
    return [...nextIds];
  }

  for (const key of changedKeys.add) {
    if (key != null) {
      nextIds.add(key);
    }
  }
  for (const key of changedKeys.delete) {
    if (key != null) {
      nextIds.delete(key);
    }
  }
  return [...nextIds];
}
