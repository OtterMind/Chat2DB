import { AuthenticationType, InputType } from '@/components/ConnectionEdit/config/enum';
import type { IConnectionConfig } from '@/components/ConnectionEdit/config/types';
import type { ISupportedDatabaseSummary } from '@/service/supportedDatabase';
import type { IDatabase } from '@/typings';

/**
 * Turns backend-registered database types that the frontend does not know about
 * into runtime entries for the database list and the connection form, so a
 * configuration-only database appears in the UI without a client rebuild.
 * Built-in types stay hardcoded; only unknown types are added.
 */

export const DYNAMIC_DATABASE_ICON = 'icon-database';

const localized = (text: string) =>
  ({ 'en-US': text, 'zh-CN': text, 'ja-JP': text, 'es-ES': text, 'ko-KR': text }) as any;

export function buildDynamicDatabase(summary: ISupportedDatabaseSummary): IDatabase {
  return {
    name: summary.name || summary.dbType,
    code: summary.dbType as IDatabase['code'],
    icon: DYNAMIC_DATABASE_ICON,
    supportDatabase: !!summary.supportDatabase,
    supportSchema: !!summary.supportSchema,
  };
}

export function buildDynamicFormConfig(summary: ISupportedDatabaseSummary): IConnectionConfig {
  const urlSample = summary.urlSample || 'jdbc:';
  return {
    type: summary.dbType as IConnectionConfig['type'],
    baseInfo: {
      items: [
        {
          defaultValue: `@${summary.name || summary.dbType}`,
          inputType: InputType.INPUT,
          labelName: localized('Name'),
          name: 'alias',
          required: true,
        },
        {
          defaultValue: urlSample,
          inputType: InputType.INPUT,
          labelName: localized('URL'),
          name: 'url',
          required: true,
        },
        {
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
                {
                  defaultValue: '',
                  inputType: InputType.INPUT,
                  labelName: localized('User'),
                  name: 'user',
                  required: false,
                },
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
        },
      ],
      // The URL field is the source of truth for dynamic types; accept any JDBC URL.
      pattern: /jdbc:\S+/,
      template: urlSample,
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
