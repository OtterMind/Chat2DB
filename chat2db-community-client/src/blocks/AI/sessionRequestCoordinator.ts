export interface AiSessionRequestOwner {
  generation: number;
  sessionId: string | null;
}

export interface AiSessionSendContext<T> {
  sessionId: string | undefined;
  history: T[];
}

export class AiSessionRequestCoordinator {
  private generation = 0;

  private currentOwner: AiSessionRequestOwner | null = null;

  private activeLoadGeneration: number | null = null;

  beginSessionLoad(sessionId: string): AiSessionRequestOwner {
    const owner = this.advance(sessionId);
    this.activeLoadGeneration = owner.generation;
    return owner;
  }

  beginNewSession(): AiSessionRequestOwner {
    const owner = this.advance(null);
    this.activeLoadGeneration = null;
    return owner;
  }

  isCurrent(owner: AiSessionRequestOwner): boolean {
    return (
      this.currentOwner?.generation === owner.generation && this.currentOwner.sessionId === owner.sessionId
    );
  }

  finishSessionLoad(owner: AiSessionRequestOwner): boolean {
    if (!this.isCurrent(owner) || this.activeLoadGeneration !== owner.generation) {
      return false;
    }
    this.activeLoadGeneration = null;
    return true;
  }

  resolveSendContext<T>(
    owner: AiSessionRequestOwner | undefined,
    currentSessionId: string | null,
    history: readonly T[],
  ): AiSessionSendContext<T> | null {
    if (owner) {
      if (!this.isCurrent(owner)) {
        return null;
      }
      if (owner.sessionId === null) {
        return { sessionId: undefined, history: [] };
      }
    }

    const sessionId = currentSessionId || undefined;
    return {
      sessionId,
      history: sessionId ? [] : [...history],
    };
  }

  private advance(sessionId: string | null): AiSessionRequestOwner {
    this.generation += 1;
    const owner = { generation: this.generation, sessionId };
    this.currentOwner = owner;
    return owner;
  }
}
