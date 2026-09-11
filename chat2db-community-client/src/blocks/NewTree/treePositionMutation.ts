import type { Key } from 'react';

export class TreePositionRefreshError extends Error {
  constructor() {
    super('The tree position was updated, but refreshing the tree failed');
    this.name = 'TreePositionRefreshError';
  }
}

export class TreePositionMutationCoordinator {
  private readonly pendingKeys = new Set<Key>();

  run(key: Key, updatePosition: () => Promise<void>, refreshTree: () => Promise<unknown>): Promise<boolean> {
    if (this.pendingKeys.has(key)) {
      return Promise.resolve(false);
    }

    this.pendingKeys.add(key);
    return Promise.resolve()
      .then(updatePosition)
      .then(async () => {
        try {
          await refreshTree();
        } catch {
          throw new TreePositionRefreshError();
        }
      })
      .then(() => true)
      .finally(() => {
        this.pendingKeys.delete(key);
      });
  }
}
