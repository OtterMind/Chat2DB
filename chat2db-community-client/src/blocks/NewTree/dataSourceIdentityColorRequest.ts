export interface DataSourceIdentityColorRequestToken {
  dataSourceId: number;
  generation: number;
}

export class DataSourceIdentityColorRequestRegistry {
  private nextGeneration = 0;
  private latestGenerationByDataSourceId = new Map<number, number>();
  private requestQueueByDataSourceId = new Map<number, Promise<void>>();
  private confirmedColorByDataSourceId = new Map<number, string | null>();

  begin(dataSourceId: number, observedConfirmedColor: string | null = null): DataSourceIdentityColorRequestToken {
    if (!this.requestQueueByDataSourceId.has(dataSourceId)) {
      this.confirmedColorByDataSourceId.set(dataSourceId, observedConfirmedColor);
    }
    const generation = this.nextGeneration + 1;
    this.nextGeneration = generation;
    this.latestGenerationByDataSourceId.set(dataSourceId, generation);
    return { dataSourceId, generation };
  }

  isLatest(token: DataSourceIdentityColorRequestToken | null | undefined) {
    return Boolean(token && this.latestGenerationByDataSourceId.get(token.dataSourceId) === token.generation);
  }

  confirm(dataSourceId: number, identityColor: string | null) {
    this.confirmedColorByDataSourceId.set(dataSourceId, identityColor);
  }

  getConfirmedColor(dataSourceId: number) {
    return this.confirmedColorByDataSourceId.get(dataSourceId) ?? null;
  }

  enqueue<T>(dataSourceId: number, request: () => Promise<T>): Promise<T> {
    const previousRequest = this.requestQueueByDataSourceId.get(dataSourceId) ?? Promise.resolve();
    const response = previousRequest.then(request);
    const queueTail = response.then(
      () => undefined,
      () => undefined,
    );
    this.requestQueueByDataSourceId.set(dataSourceId, queueTail);
    void queueTail.then(() => {
      if (this.requestQueueByDataSourceId.get(dataSourceId) === queueTail) {
        this.requestQueueByDataSourceId.delete(dataSourceId);
      }
    });
    return response;
  }
}
