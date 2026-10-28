export interface SqlExecutionPosition {
  lineNumber: number;
  column: number;
}

export interface SqlExecutionTargetSnapshot {
  selectedSql: string;
  cursorPosition: SqlExecutionPosition | null;
}

export interface ResolvedSqlExecutionTarget {
  sql: string;
  single: boolean;
}

export function createSqlExecutionTargetSnapshot(
  selectedSql: string | null | undefined,
  cursorPosition: SqlExecutionPosition | null | undefined,
): SqlExecutionTargetSnapshot {
  return {
    selectedSql: selectedSql || '',
    cursorPosition: cursorPosition
      ? {
          lineNumber: cursorPosition.lineNumber,
          column: cursorPosition.column,
        }
      : null,
  };
}

export function resolveSqlExecutionTarget(
  snapshot: SqlExecutionTargetSnapshot,
  getCursorSql: (position: SqlExecutionPosition) => string,
): ResolvedSqlExecutionTarget {
  const sql = snapshot.selectedSql || (snapshot.cursorPosition ? getCursorSql(snapshot.cursorPosition) : '');
  return {
    sql,
    single: !snapshot.selectedSql,
  };
}
