import { shouldKeepExistingExecutionResults } from './sqlExecutionBatch';

export interface SqlExecutionRetentionPreferences {
  keepOutputHistory: boolean;
  keepResultHistory: boolean;
  resetResultSession: boolean;
}

export interface SqlExecutionRetentionPlan {
  keepExistingOutput: boolean;
  keepExistingResults: boolean;
}

export function planSqlExecutionRetention(
  preferences: SqlExecutionRetentionPreferences,
): SqlExecutionRetentionPlan {
  return {
    keepExistingOutput: preferences.keepOutputHistory,
    keepExistingResults: shouldKeepExistingExecutionResults(
      preferences.keepResultHistory,
      preferences.resetResultSession,
    ),
  };
}
