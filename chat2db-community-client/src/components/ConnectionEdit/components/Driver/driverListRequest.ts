export interface DriverListRequestCallbacks<T> {
  onSuccess: (result: T) => void;
  onError?: (error: unknown) => void;
}

export type DriverListRequestScope = symbol;

export class DriverListRequestOwner {
  private generation = 0;

  private scope?: DriverListRequestScope;

  private disposed = true;

  createScope(): DriverListRequestScope {
    return Symbol('driver-list-request-scope');
  }

  activate(scope: DriverListRequestScope) {
    this.generation += 1;
    this.scope = scope;
    this.disposed = false;
  }

  dispose(scope: DriverListRequestScope) {
    if (this.scope !== scope) {
      return;
    }
    this.generation += 1;
    this.disposed = true;
  }

  async run<T>(
    expectedScope: DriverListRequestScope,
    request: () => Promise<T>,
    callbacks: DriverListRequestCallbacks<T>,
  ): Promise<void> {
    if (this.disposed || expectedScope !== this.scope) {
      return;
    }
    const generation = this.generation + 1;
    this.generation = generation;
    try {
      const result = await request();
      if (this.owns(expectedScope, generation)) {
        callbacks.onSuccess(result);
      }
    } catch (error) {
      if (this.owns(expectedScope, generation)) {
        callbacks.onError?.(error);
      }
    }
  }

  private owns(expectedScope: DriverListRequestScope, generation: number) {
    return !this.disposed && this.scope === expectedScope && this.generation === generation;
  }
}
