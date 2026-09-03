import type { DbSessionKillType, IDbSession, IDbSessionKillResult, ISessionRequest } from '@/service/sql';

export const SESSION_KILL_TYPES: DbSessionKillType[] = ['QUERY', 'CONNECTION'];
export const SESSION_DATABASE_EMPTY_VALUE = '-';
export const SESSION_AUTO_REFRESH_INTERVALS = [0, 15, 30, 60];

export interface ISessionFilters {
  keyword?: string;
  user?: string;
  database?: string;
  state?: string;
  minDurationSeconds?: number | null;
}

export function filterDbSessions(sessions: IDbSession[], filters: ISessionFilters = {}) {
  const normalizedKeyword = normalizeFilter(filters.keyword);
  const normalizedUser = normalizeFilter(filters.user);
  const normalizedDatabase = normalizeFilter(filters.database);
  const normalizedState = normalizeFilter(filters.state);
  const minDurationSeconds = Number(filters.minDurationSeconds) || 0;

  if (!normalizedKeyword && !normalizedUser && !normalizedDatabase && !normalizedState && !minDurationSeconds) {
    return sessions;
  }

  return sessions.filter((session) => {
    if (minDurationSeconds && session.time < minDurationSeconds) {
      return false;
    }
    if (normalizedUser && !fieldIncludes(session.user, normalizedUser)) {
      return false;
    }
    if (normalizedDatabase && normalizeNullableField(session.db) !== normalizedDatabase) {
      return false;
    }
    if (normalizedState && !fieldIncludes(normalizeNullableField(session.state), normalizedState)) {
      return false;
    }
    if (!normalizedKeyword) {
      return true;
    }
    return [
      session.id,
      session.user,
      session.host,
      session.db,
      session.command,
      session.state,
      session.info,
    ].some((value) => fieldIncludes(value, normalizedKeyword));
  });
}

export function createSessionRequest(dataSourceId?: number, databaseName?: string): ISessionRequest | null {
  if (!dataSourceId) {
    return null;
  }

  return {
    dataSourceId,
    databaseName: databaseName || undefined,
  };
}

export function createKillSessionRequest(
  baseRequest: ISessionRequest,
  session: Pick<IDbSession, 'id'>,
  killType: DbSessionKillType,
) {
  return {
    ...baseRequest,
    connectionId: session.id,
    killType,
  };
}

export function formatKillSessionResult(session: Pick<IDbSession, 'id' | 'user'>, killType: DbSessionKillType) {
  return `${killType}:${session.id}:${session.user}`;
}

export function formatKillSessionSql(session: Pick<IDbSession, 'id'>, killType: DbSessionKillType) {
  return killType === 'CONNECTION' ? `KILL CONNECTION ${session.id}` : `KILL QUERY ${session.id}`;
}

export function formatKillOutcomeResult(result: IDbSessionKillResult) {
  return `${result.status}:${result.connectionId}:${result.sql}`;
}

export function isKillActionDisabled(session: Pick<IDbSession, 'current'>) {
  return Boolean(session.current);
}

function normalizeFilter(value: unknown) {
  return String(value ?? '')
    .trim()
    .toLowerCase();
}

function normalizeNullableField(value: unknown) {
  const normalized = normalizeFilter(value);
  return normalized || SESSION_DATABASE_EMPTY_VALUE;
}

function fieldIncludes(value: unknown, normalizedKeyword: string) {
  return normalizeFilter(value).includes(normalizedKeyword);
}
