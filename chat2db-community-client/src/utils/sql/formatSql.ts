import { DatabaseTypeCode } from '@/constants';
import sqlServer from '@/service/sql';
import { formatSqlWithRequester } from './formatSqlRequest';

export function formatSql(sql: string, dbType?: DatabaseTypeCode): Promise<string> {
  return formatSqlWithRequester((request) => sqlServer.sqlFormat(request), sql, dbType);
}
