export type SavedConsoleSaveMode = 'manual' | 'automatic';

export interface SavedConsoleMutationResult<T> {
  executed: boolean;
  current: boolean;
  value?: T;
}

interface SavedConsoleMutationState {
  tail: Promise<void>;
  generation: number;
  publishDesired: boolean;
  retainDraftBlock: boolean;
  pending: number;
}

export class SavedConsoleMutationCoordinator {
  private readonly states = new Map<number, SavedConsoleMutationState>();

  save<T>(consoleId: number, mode: SavedConsoleSaveMode, mutation: () => Promise<T>) {
    const state = this.getState(consoleId);
    if (mode === 'automatic' && !state.publishDesired) {
      return Promise.resolve<SavedConsoleMutationResult<T>>({
        executed: false,
        current: false,
      });
    }

    if (mode === 'manual') {
      const previousPublishDesired = state.publishDesired;
      const previousRetainDraftBlock = state.retainDraftBlock;
      state.generation += 1;
      state.publishDesired = true;
      state.retainDraftBlock = false;
      const generation = state.generation;
      return this.enqueue(
        consoleId,
        state,
        mutation,
        () => state.generation === generation && state.publishDesired,
        () => {
          if (state.generation === generation && state.publishDesired) {
            state.publishDesired = previousPublishDesired;
            state.retainDraftBlock = previousRetainDraftBlock;
          }
        },
      );
    }
    const generation = state.generation;

    return this.enqueue(consoleId, state, mutation, () => {
      return state.generation === generation && state.publishDesired;
    });
  }

  remove<T>(consoleId: number, retainDraftBlock: boolean, mutation: () => Promise<T>) {
    const state = this.getState(consoleId);
    const previousPublishDesired = state.publishDesired;
    const previousRetainDraftBlock = state.retainDraftBlock;
    state.generation += 1;
    state.publishDesired = false;
    state.retainDraftBlock = retainDraftBlock;
    const generation = state.generation;

    return this.enqueue(
      consoleId,
      state,
      mutation,
      () => state.generation === generation && !state.publishDesired,
      () => {
        if (state.generation === generation && !state.publishDesired) {
          state.publishDesired = previousPublishDesired;
          state.retainDraftBlock = previousRetainDraftBlock;
        }
      },
    );
  }

  private getState(consoleId: number): SavedConsoleMutationState {
    const existing = this.states.get(consoleId);
    if (existing) {
      return existing;
    }

    const state: SavedConsoleMutationState = {
      tail: Promise.resolve(),
      generation: 0,
      publishDesired: true,
      retainDraftBlock: false,
      pending: 0,
    };
    this.states.set(consoleId, state);
    return state;
  }

  private enqueue<T>(
    consoleId: number,
    state: SavedConsoleMutationState,
    mutation: () => Promise<T>,
    isCurrent: () => boolean,
    onError?: () => void,
  ): Promise<SavedConsoleMutationResult<T>> {
    state.pending += 1;
    const execution = state.tail.then(mutation);
    state.tail = execution.then(
      () => undefined,
      () => undefined,
    );

    return execution.then(
      (value) => {
        const current = isCurrent();
        this.finish(consoleId, state);
        return {
          executed: true,
          current,
          value,
        };
      },
      (error) => {
        onError?.();
        this.finish(consoleId, state);
        throw error;
      },
    );
  }

  private finish(consoleId: number, state: SavedConsoleMutationState) {
    state.pending -= 1;
    if (state.pending === 0 && (state.publishDesired || !state.retainDraftBlock)) {
      this.states.delete(consoleId);
    }
  }
}

export const savedConsoleMutationCoordinator = new SavedConsoleMutationCoordinator();
