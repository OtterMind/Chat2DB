import type { SSERequestCallbacks, SSERequestHandle } from './index';

interface ActiveRequest {
  generation: number;
  handle: SSERequestHandle;
}

/** Owns one active request for a single useSSERequest hook instance. */
export class SSERequestOwner {
  private generation = 0;
  private activeRequest?: ActiveRequest;

  begin() {
    this.generation += 1;
    const generation = this.generation;
    const previousRequest = this.activeRequest;
    this.activeRequest = undefined;
    previousRequest?.handle.stop();
    return generation;
  }

  attach(generation: number, handle: SSERequestHandle) {
    if (!this.isCurrentGeneration(generation)) {
      handle.stop();
      return false;
    }

    this.activeRequest = { generation, handle };
    return true;
  }

  isCurrentGeneration(generation: number) {
    return this.generation === generation;
  }

  owns(generation: number, handle?: SSERequestHandle) {
    return handle ? this.isActive(generation, handle) : this.isCurrentGeneration(generation);
  }

  isActive(generation: number, handle: SSERequestHandle) {
    return (
      this.isCurrentGeneration(generation) &&
      this.activeRequest?.generation === generation &&
      this.activeRequest.handle === handle
    );
  }

  release(generation: number, handle: SSERequestHandle) {
    if (this.isActive(generation, handle)) {
      this.activeRequest = undefined;
    }
  }

  stop() {
    this.generation += 1;
    const activeRequest = this.activeRequest;
    this.activeRequest = undefined;
    activeRequest?.handle.stop();
    return Boolean(activeRequest);
  }
}

export const guardSSERequestCallbacks = <Output>(
  callbacks: SSERequestCallbacks<Output>,
  isActive: () => boolean,
): SSERequestCallbacks<Output> => ({
  onSuccess: (chunks) => {
    if (isActive()) {
      callbacks.onSuccess(chunks);
    }
  },
  onError: (error) => {
    if (isActive()) {
      callbacks.onError(error);
    }
  },
  onUpdate: (chunk) => {
    if (isActive()) {
      callbacks.onUpdate(chunk);
    }
  },
  onStop: () => {
    if (isActive()) {
      callbacks.onStop?.();
    }
  },
});
