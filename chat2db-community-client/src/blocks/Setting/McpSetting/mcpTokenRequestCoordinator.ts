export interface McpTokenRequestOwner {
  generation: number;
  kind: 'mount' | 'reset';
}

export class McpTokenRequestCoordinator {
  private generation = 0;

  private resetOwner: McpTokenRequestOwner | null = null;

  beginMount(): McpTokenRequestOwner {
    this.generation += 1;
    return { generation: this.generation, kind: 'mount' };
  }

  beginReset(): McpTokenRequestOwner | null {
    if (this.resetOwner) {
      return null;
    }
    this.generation += 1;
    const owner: McpTokenRequestOwner = { generation: this.generation, kind: 'reset' };
    this.resetOwner = owner;
    return owner;
  }

  isCurrent(owner: McpTokenRequestOwner): boolean {
    return this.generation === owner.generation;
  }

  finishReset(owner: McpTokenRequestOwner): boolean {
    if (!this.isCurrent(owner) || this.resetOwner?.generation !== owner.generation) {
      return false;
    }
    this.resetOwner = null;
    return true;
  }

  invalidate(): void {
    this.generation += 1;
    this.resetOwner = null;
  }
}
