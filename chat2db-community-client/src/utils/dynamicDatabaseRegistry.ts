import { AuthenticationType, InputType } from '@/components/ConnectionEdit/config/enum';
import type { IConnectionConfig, IFormItem } from '@/components/ConnectionEdit/config/types';
import type { ISupportedDatabaseSummary } from '@/service/supportedDatabase';
import type { IDatabase } from '@/typings';

/**
 * Turns backend-registered database types that the frontend does not know about
 * into runtime entries for the database list and the connection form, so a
 * configuration-only database appears in the UI without a client rebuild.
 * Built-in types stay hardcoded; only unknown types are added.
 */

/** Neutral colourful glyph from the shared sprite; has a -dark variant. */
export const DYNAMIC_DATABASE_ICON = 'icon-colourful-table';

const localized = (text: string) =>
  ({ 'en-US': text, 'zh-CN': text, 'ja-JP': text, 'es-ES': text, 'ko-KR': text }) as any;

export function buildDynamicDatabase(summary: ISupportedDatabaseSummary): IDatabase {
  return {
    name: summary.name || summary.dbType,
    code: summary.dbType as IDatabase['code'],
    icon: DYNAMIC_DATABASE_ICON,
    iconExistDark: true,
    supportDatabase: !!summary.supportDatabase,
    supportSchema: !!summary.supportSchema,
  };
}

interface ParsedUrlSample {
  scheme: string;
  host: string;
  port: string;
  database: string;
}

/** Parses a host/port style JDBC sample like jdbc:foo://localhost:1234/db. */
export function parseHostPortUrlSample(urlSample: string | null | undefined): ParsedUrlSample | null {
  const match = /^(jdbc:[A-Za-z0-9._-]+):\/\/([^:/?]+):(\d+)(?:\/(.*))?$/.exec(urlSample || '');
  if (!match) {
    return null;
  }
  return { scheme: match[1], host: match[2], port: match[3], database: match[4] || '' };
}

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const textItem = (name: string, label: string, defaultValue: string, required: boolean, width?: string): IFormItem => ({
  defaultValue,
  inputType: InputType.INPUT,
  labelName: localized(label),
  name,
  required,
  ...(width ? { styles: { width } } : {}),
});

const authenticationItem = (): IFormItem => ({
  defaultValue: AuthenticationType.USERANDPASSWORD,
  inputType: InputType.SELECT,
  labelName: localized('Authentication'),
  name: 'authenticationType',
  required: true,
  selects: [
    {
      label: 'User&Password',
      value: AuthenticationType.USERANDPASSWORD,
      items: [
        textItem('user', 'User', '', false),
        {
          defaultValue: '',
          inputType: InputType.PASSWORD,
          labelName: localized('Password'),
          name: 'password',
          required: false,
        },
      ],
    },
    { label: 'NONE', value: AuthenticationType.NONE, items: [] },
  ],
  styles: { width: '50%' },
});

export function buildDynamicFormConfig(summary: ISupportedDatabaseSummary): IConnectionConfig {
  const urlSample = summary.urlSample || 'jdbc:';
  const parsed = parseHostPortUrlSample(urlSample);
  const alias = textItem('alias', 'Name', `@${summary.name || summary.dbType}`, true);

  if (!parsed) {
    // File/embedded style URL: the URL field is the single source of truth.
    return {
      type: summary.dbType as IConnectionConfig['type'],
      baseInfo: {
        items: [alias, textItem('url', 'URL', urlSample, true), authenticationItem()],
        pattern: /jdbc:\S+/,
        template: urlSample,
      },
      ssh: { items: [] },
    };
  }

  // Host/port style: mirror the built-in configs so ConnectionEdit's
  // pattern/template machinery keeps host, port, database, and URL in sync.
  // Group convention (same as built-ins): 1=host, 2=port, 4=database.
  const pattern = new RegExp(`${escapeRegExp(parsed.scheme)}:\\/\\/(.*):(\\d+)(\\/(.*))?`);
  const template = `${parsed.scheme}://{host}:{port}/{database}`;
  return {
    type: summary.dbType as IConnectionConfig['type'],
    baseInfo: {
      items: [
        alias,
        textItem('host', 'Host', parsed.host, true, '70%'),
        textItem('port', 'Port', parsed.port, false, '30%'),
        authenticationItem(),
        textItem('database', 'Database', parsed.database, false),
        textItem('url', 'URL', urlSample, true),
      ],
      pattern,
      template,
    },
    ssh: { items: [] },
  };
}

export interface DynamicDatabaseRegistries {
  databaseMap: Record<string, IDatabase>;
  databaseTypeList: IDatabase[];
  dataSourceFormConfigs: IConnectionConfig[];
}

/**
 * Registers every unknown summary into the given registries. Returns the list
 * of database types that were added. Known types and blank entries are
 * skipped, and a type is never registered twice.
 */
export function registerDynamicDatabases(
  summaries: ISupportedDatabaseSummary[] | null | undefined,
  registries: DynamicDatabaseRegistries,
): string[] {
  const added: string[] = [];
  for (const summary of summaries || []) {
    const dbType = summary?.dbType?.trim();
    if (!dbType || registries.databaseMap[dbType]) {
      continue;
    }
    const database = buildDynamicDatabase(summary);
    registries.databaseMap[dbType] = database;
    registries.databaseTypeList.push(database);
    if (!registries.dataSourceFormConfigs.some((config) => config.type === (dbType as any))) {
      registries.dataSourceFormConfigs.push(buildDynamicFormConfig(summary));
    }
    added.push(dbType);
  }
  return added;
}
