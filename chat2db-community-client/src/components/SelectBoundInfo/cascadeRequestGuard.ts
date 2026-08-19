export type CascadeRequestLevel = 'database' | 'schema';

export interface CascadeRequestContext {
  dataSourceId?: number;
  databaseName?: string;
}

export interface CascadeRequestToken {
  level: CascadeRequestLevel;
  generation: number;
  contextKey: string;
}

export interface CascadeRequestGuard {
  active: boolean;
  generations: Record<CascadeRequestLevel, number>;
}

export function createCascadeRequestGuard(): CascadeRequestGuard {
  return {
    active: true,
    generations: {
      database: 0,
      schema: 0,
    },
  };
}

export function getCascadeRequestContextKey(level: CascadeRequestLevel, context: CascadeRequestContext): string {
  const dataSourceId = context.dataSourceId ?? '';
  return level === 'database'
    ? `datasource:${dataSourceId}`
    : `datasource:${dataSourceId}:database:${context.databaseName ?? ''}`;
}

export function beginCascadeRequest(
  guard: CascadeRequestGuard,
  level: CascadeRequestLevel,
  context: CascadeRequestContext,
): CascadeRequestToken {
  const generation = guard.generations[level] + 1;
  guard.generations[level] = generation;
  return {
    level,
    generation,
    contextKey: getCascadeRequestContextKey(level, context),
  };
}

export function invalidateCascadeRequest(guard: CascadeRequestGuard, level: CascadeRequestLevel) {
  guard.generations[level] += 1;
}

export function activateCascadeRequestGuard(guard: CascadeRequestGuard) {
  guard.active = true;
}

export function disposeCascadeRequestGuard(guard: CascadeRequestGuard) {
  guard.active = false;
  invalidateCascadeRequest(guard, 'database');
  invalidateCascadeRequest(guard, 'schema');
}

export function isCascadeRequestCurrent(
  guard: CascadeRequestGuard,
  token: CascadeRequestToken,
  context: CascadeRequestContext,
): boolean {
  return (
    guard.active &&
    guard.generations[token.level] === token.generation &&
    token.contextKey === getCascadeRequestContextKey(token.level, context)
  );
}
