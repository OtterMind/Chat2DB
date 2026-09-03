export type SqlParserRequestScope = symbol;
export type SqlParserRequestStatus = 'committed' | 'stale';

export const isCommittedSqlParserRequest = (status: SqlParserRequestStatus) => status === 'committed';

export async function runAfterCommittedSqlParser(
  parse: () => Promise<SqlParserRequestStatus>,
  execute: () => void | Promise<void>,
): Promise<boolean> {
  const status = await parse();
  if (!isCommittedSqlParserRequest(status)) {
    return false;
  }
  await execute();
  return true;
}

export async function runAfterCommittedSqlParserWithSnapshot<T>(
  capture: () => T,
  parse: () => Promise<SqlParserRequestStatus>,
  execute: (snapshot: T) => void | Promise<void>,
): Promise<boolean> {
  const snapshot = capture();
  return runAfterCommittedSqlParser(parse, () => execute(snapshot));
}

export interface SqlParserRequestContext {
  scope: SqlParserRequestScope;
  databaseKey: string;
  sql: string;
  model: unknown;
  modelVersion: number | null;
}

export class SqlParserRequestCoordinator {
  private generation = 0;

  private active = true;

  createScope(): SqlParserRequestScope {
    return Symbol('sql-parser-request-scope');
  }

  activate() {
    this.generation += 1;
    this.active = true;
  }

  invalidate() {
    this.generation += 1;
  }

  dispose() {
    this.generation += 1;
    this.active = false;
  }

  async run<T>(
    context: SqlParserRequestContext,
    readCurrentContext: () => SqlParserRequestContext,
    request: () => Promise<T>,
    commit: (result: T) => void,
  ): Promise<SqlParserRequestStatus> {
    if (!this.active || !isSameSqlParserRequestContext(context, readCurrentContext())) {
      return 'stale';
    }
    const generation = this.generation + 1;
    this.generation = generation;
    let result: T;
    try {
      result = await request();
    } catch (error) {
      if (!this.isCurrent(generation, context, readCurrentContext())) {
        return 'stale';
      }
      throw error;
    }
    if (!this.isCurrent(generation, context, readCurrentContext())) {
      return 'stale';
    }
    commit(result);
    return 'committed';
  }

  private isCurrent(generation: number, context: SqlParserRequestContext, currentContext: SqlParserRequestContext) {
    return this.active && this.generation === generation && isSameSqlParserRequestContext(context, currentContext);
  }
}

function isSameSqlParserRequestContext(left: SqlParserRequestContext, right: SqlParserRequestContext) {
  return (
    left.scope === right.scope &&
    left.databaseKey === right.databaseKey &&
    left.sql === right.sql &&
    left.model === right.model &&
    left.modelVersion === right.modelVersion
  );
}
