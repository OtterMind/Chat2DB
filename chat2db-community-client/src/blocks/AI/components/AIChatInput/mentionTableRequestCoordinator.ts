export interface MentionTableRequestContext {
  dataSourceId?: string | number | null;
  databaseName?: string | null;
  schemaName?: string | null;
}

export interface MentionTableRequestOwner<TPage extends string> {
  generation: number;
  page: TPage;
  contextKey: string;
  searchKey: string;
}

export type MentionSuggestionDecision = 'wait' | 'open' | 'close' | 'ignore';

export class MentionSuggestionResolution {
  private tableResolved: boolean;

  private tableHasCandidates = false;

  private knowledgeResolved = false;

  private knowledgeHasCandidates = false;

  private decisionIssued = false;

  constructor(expectTableResult: boolean) {
    this.tableResolved = !expectTableResult;
  }

  resolveTable(hasCandidates: boolean): MentionSuggestionDecision {
    this.tableResolved = true;
    this.tableHasCandidates = hasCandidates;
    return this.resolve();
  }

  resolveKnowledge(hasCandidates: boolean): MentionSuggestionDecision {
    this.knowledgeResolved = true;
    this.knowledgeHasCandidates = hasCandidates;
    return this.resolve();
  }

  private resolve(): MentionSuggestionDecision {
    if (this.decisionIssued) {
      return 'ignore';
    }
    if (this.tableHasCandidates || this.knowledgeHasCandidates) {
      this.decisionIssued = true;
      return 'open';
    }
    if (this.tableResolved && this.knowledgeResolved) {
      this.decisionIssued = true;
      return 'close';
    }
    return 'wait';
  }
}

export class MentionTableRequestCoordinator<TPage extends string> {
  private generation = 0;

  private currentOwner: MentionTableRequestOwner<TPage> | null = null;

  beginRequest(
    page: TPage,
    context: MentionTableRequestContext,
    searchKey: string,
  ): MentionTableRequestOwner<TPage> {
    this.generation += 1;
    const owner = {
      generation: this.generation,
      page,
      contextKey: JSON.stringify([context.dataSourceId, context.databaseName, context.schemaName]),
      searchKey,
    };
    this.currentOwner = owner;
    return owner;
  }

  invalidate(): void {
    this.generation += 1;
    this.currentOwner = null;
  }

  isCurrent(owner: MentionTableRequestOwner<TPage>): boolean {
    return (
      this.currentOwner?.generation === owner.generation &&
      this.currentOwner.page === owner.page &&
      this.currentOwner.contextKey === owner.contextKey &&
      this.currentOwner.searchKey === owner.searchKey
    );
  }

  getOwnedErrorPage(owner: MentionTableRequestOwner<TPage>): TPage | null {
    return this.isCurrent(owner) ? owner.page : null;
  }
}
