export class DashboardDetailRequestOwner {
  private generation = 0;

  invalidate() {
    this.generation += 1;
  }

  async run<T>(request: () => Promise<T>, commit: (result: T) => void): Promise<void> {
    const generation = this.generation + 1;
    this.generation = generation;
    const result = await request();
    if (this.generation === generation) {
      commit(result);
    }
  }
}
