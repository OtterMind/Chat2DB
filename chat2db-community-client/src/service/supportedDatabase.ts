import createRequest from './base';

/** One database type registered by a backend plugin, served by /api/database/supported. */
export interface ISupportedDatabaseSummary {
  dbType: string;
  name: string;
  supportDatabase: boolean;
  supportSchema: boolean;
  sqlDialect?: string | null;
  jdbcDriverClass?: string | null;
  urlSample?: string | null;
}

const listSupported = createRequest<Record<string, never>, ISupportedDatabaseSummary[]>(
  '/api/database/supported',
  {},
);

export default { listSupported };
