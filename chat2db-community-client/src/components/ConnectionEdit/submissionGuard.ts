export class SubmissionGuard {
  private pending = false;

  run<T>(submit: () => Promise<T>): Promise<T> | null {
    if (this.pending) {
      return null;
    }

    this.pending = true;
    return Promise.resolve()
      .then(submit)
      .finally(() => {
        this.pending = false;
      });
  }
}
