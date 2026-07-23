import { AuthenticationType, InputType } from '@/components/ConnectionEdit/config/enum';
import type { IConnectionConfig, IFormItem } from '@/components/ConnectionEdit/config/types';
import type { ISupportedDatabaseSummary } from '@/service/supportedDatabase';
import type { IDatabase } from '@/typings';

/**
 * Turns backend-registered database types that the frontend does not know about
 * into runtime entries for the database list, the icon sprite, and the
 * connection form, so a configuration-only database appears in the UI without
 * a client rebuild. Built-in types stay hardcoded; only unknown types are
 * added. The shared form items (env, storage, port, ssh) are injected by the
 * caller so dynamic forms reuse exactly the objects the built-in forms use.
 */

/** Fallback glyph from the shared colourful sprite; has a -dark variant. */
export const DYNAMIC_DATABASE_ICON = 'icon-colourful-table';

const localized = (text: string) =>
  ({ 'en-US': text, 'zh-CN': text, 'ja-JP': text, 'es-ES': text, 'ko-KR': text }) as any;

export interface SharedFormItems {
  envItem: IFormItem;
  storageItem: IFormItem;
  portItem: IFormItem;
  sshConfig: IConnectionConfig['ssh'];
}

/** Sprite symbol id for a dynamic database's own icon. */
export const dynamicIconCode = (dbType: string) => `icon-colourful-dyn-${dbType.toLowerCase()}`;

/**
 * Builds one sprite <symbol> from a backend-provided inline SVG, or null when
 * the summary has no usable icon.
 */
export function buildIconSymbol(summary: ISupportedDatabaseSummary): string | null {
  const svg = summary.icon || '';
  const match = /<svg[^>]*viewBox="([^"]+)"[^>]*>([\s\S]*)<\/svg>/.exec(svg);
  if (!match) {
    return null;
  }
  return `<symbol id="${dynamicIconCode(summary.dbType)}" viewBox="${match[1]}">${match[2]}</symbol>`;
}

/** Wraps symbols into one hidden sprite ready for DOM injection. */
export function buildIconSprite(summaries: ISupportedDatabaseSummary[]): string {
  const symbols = summaries
    .map(buildIconSymbol)
    .filter(Boolean)
    .join('');
  return symbols ? `<svg style="position:absolute;width:0;height:0;overflow:hidden" aria-hidden="true">${symbols}</svg>` : '';
}

export function buildDynamicDatabase(summary: ISupportedDatabaseSummary): IDatabase {
  const hasOwnIcon = !!buildIconSymbol(summary);
  return {
    name: summary.name || summary.dbType,
    code: summary.dbType as IDatabase['code'],
    icon: hasOwnIcon ? dynamicIconCode(summary.dbType) : DYNAMIC_DATABASE_ICON,
    ...(hasOwnIcon ? {} : { iconExistDark: true }),
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

/** Parses a file/embedded style sample like jdbc:hsqldb:file:demo. */
export function parseFileUrlSample(urlSample: string | null | undefined): { prefix: string; file: string } | null {
  const match = /^(jdbc:[A-Za-z0-9._-]+:(?:file:)?)([^/:][\s\S]*)?$/.exec(urlSample || '');
  if (!match || (urlSample || '').includes('://')) {
    return null;
  }
  return { prefix: match[1], file: match[2] || '' };
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

export function buildDynamicFormConfig(
  summary: ISupportedDatabaseSummary,
  shared: SharedFormItems,
): IConnectionConfig {
  const urlSample = summary.urlSample || 'jdbc:';
  const alias = textItem('alias', 'Name', `@${summary.name || summary.dbType}`, true);
  const ssh = shared.sshConfig || { items: [] };
  const hostPort = parseHostPortUrlSample(urlSample);

  if (hostPort) {
    // Mirror the built-in configs so ConnectionEdit's pattern/template
    // machinery keeps host, port, database, and URL in sync.
    // Group convention (same as built-ins): 1=host, 2=port, 4=database.
    const pattern = new RegExp(`${escapeRegExp(hostPort.scheme)}:\\/\\/(.*):(\\d+)(\\/(.*))?`);
    const template = `${hostPort.scheme}://{host}:{port}/{database}`;
    return {
      type: summary.dbType as IConnectionConfig['type'],
      baseInfo: {
        items: [
          alias,
          shared.envItem,
          shared.storageItem,
          textItem('host', 'Host', hostPort.host, true, '70%'),
          { ...shared.portItem, defaultValue: hostPort.port },
          authenticationItem(),
          textItem('database', 'Database', hostPort.database, false),
          textItem('url', 'URL', urlSample, true),
        ],
        pattern,
        template,
      },
      ssh,
    };
  }

  const file = parseFileUrlSample(urlSample);
  if (file) {
    // File/embedded style, mirroring the built-in SQLite shape with a real
    // file picker; the file path and URL stay in sync via pattern/template.
    return {
      type: summary.dbType as IConnectionConfig['type'],
      baseInfo: {
        items: [
          alias,
          shared.envItem,
          shared.storageItem,
          {
            defaultValue: file.file,
            inputType: InputType.FILE,
            labelName: localized('File'),
            name: 'file',
            required: true,
          },
          authenticationItem(),
          textItem('url', 'URL', urlSample, true),
        ],
        pattern: new RegExp(`${escapeRegExp(file.prefix)}(.*)?`),
        template: `${file.prefix}{file}`,
      },
      ssh,
    };
  }

  // Last resort: the URL field is the single source of truth.
  return {
    type: summary.dbType as IConnectionConfig['type'],
    baseInfo: {
      items: [alias, shared.envItem, shared.storageItem, textItem('url', 'URL', urlSample, true), authenticationItem()],
      pattern: /jdbc:\S+/,
      template: urlSample,
    },
    ssh,
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
  shared: SharedFormItems,
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
      registries.dataSourceFormConfigs.push(buildDynamicFormConfig(summary, shared));
    }
    added.push(dbType);
  }
  return added;
}
