import type { DatabaseTypeCode } from '@/constants';

export interface SqlFormatRequest {
  sql: string;
  dbType?: DatabaseTypeCode;
}

export type SqlFormatRequester = (request: SqlFormatRequest) => Promise<string>;

export function formatSqlWithRequester(
  requester: SqlFormatRequester,
  sql: string,
  dbType?: DatabaseTypeCode,
): Promise<string> {
  return Promise.resolve()
    .then(() => requester({ sql, dbType }))
    .catch((error) => {
      console.error('Server-side SQL formatting error:', error);
      return sql;
    });
}
