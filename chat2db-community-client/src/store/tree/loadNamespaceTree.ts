interface LatestLoadEntry<T> {
  pending: boolean;
  priority: number;
  publicPromise: Promise<T>;
  redirect: (latestPromise: Promise<T>) => void;
  invalidate: () => void;
}

interface LatestLoadState<T> {
  latestEntry?: LatestLoadEntry<T>;
}

export interface LatestLoadOptions {
  supersede: boolean;
  priority?: number;
}

export class LatestLoadCoordinator<K, T> {
  private readonly states = new Map<K, LatestLoadState<T>>();

  constructor(private readonly createInvalidatedResult: () => T) {}

  run(key: K, options: LatestLoadOptions, load: (isCurrent: () => boolean) => Promise<T>): Promise<T> {
    const priority = options.priority ?? 0;
    let state = this.states.get(key);
    if (state?.latestEntry && (!options.supersede || state.latestEntry.priority > priority)) {
      return state.latestEntry.publicPromise;
    }
    if (!state) {
      state = {};
      this.states.set(key, state);
    }

    let resolvePublic!: (value: T | PromiseLike<T>) => void;
    let rejectPublic!: (error: unknown) => void;
    let settledOrRedirected = false;
    const publicPromise = new Promise<T>((resolve, reject) => {
      resolvePublic = resolve;
      rejectPublic = reject;
    });
    const entry: LatestLoadEntry<T> = {
      pending: true,
      priority,
      publicPromise,
      redirect: (latestPromise) => {
        if (!settledOrRedirected) {
          settledOrRedirected = true;
          resolvePublic(latestPromise);
        }
      },
      invalidate: () => {
        if (!settledOrRedirected) {
          settledOrRedirected = true;
          entry.pending = false;
          resolvePublic(this.createInvalidatedResult());
        }
      },
    };
    const previousEntry = state.latestEntry;
    state.latestEntry = entry;
    previousEntry?.redirect(publicPromise);

    const isCurrent = () => this.states.get(key) === state && state.latestEntry === entry;
    let loadPromise: Promise<T>;
    try {
      loadPromise = load(isCurrent);
    } catch (error) {
      loadPromise = Promise.reject(error);
    }

    void loadPromise
      .then(
        (result) => {
          if (!settledOrRedirected) {
            settledOrRedirected = true;
            resolvePublic(result);
          }
        },
        (error) => {
          if (!settledOrRedirected) {
            settledOrRedirected = true;
            rejectPublic(error);
          }
        },
      )
      .finally(() => {
        entry.pending = false;
        if (this.states.get(key) === state && state.latestEntry === entry) {
          this.states.delete(key);
        }
      });
    return publicPromise;
  }

  hasPending(key: K): boolean {
    const state = this.states.get(key);
    return state?.latestEntry?.pending === true;
  }

  invalidate(key: K) {
    const state = this.states.get(key);
    this.states.delete(key);
    state?.latestEntry?.invalidate();
  }

  invalidateMatching(matches: (key: K) => boolean) {
    for (const key of [...this.states.keys()]) {
      if (matches(key)) {
        this.invalidate(key);
      }
    }
  }

  invalidateAll() {
    for (const key of [...this.states.keys()]) {
      this.invalidate(key);
    }
  }
}

export type NamespaceTreeLoadResult<T> = { ok: true; items: T[] } | { ok: false; error: unknown };

export async function loadNamespaceTree<T>(request: () => Promise<T[]>): Promise<NamespaceTreeLoadResult<T>> {
  try {
    return { ok: true, items: await request() };
  } catch (error) {
    return { ok: false, error };
  }
}
