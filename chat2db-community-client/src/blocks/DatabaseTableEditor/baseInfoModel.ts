import type { IEditTableInfo, IColumnItemNew } from '@/typings';
import type {
  ICharset,
  ICollation,
  IColumnTypes,
  IDefaultValue,
  IEngineType,
  IIndexTypes,
} from '@/typings/database';

export interface IOption {
  label: string;
  value: string | number | null;
  charset?: string | null;
  defaultCollationName?: string | null;
}

interface IColumnTypesOption extends IColumnTypes {
  label: string;
  value: string | number | null;
}

export interface IDatabaseSupportFieldOptions {
  columnTypes: IColumnTypesOption[];
  charsets: IOption[];
  collations: IOption[];
  indexTypes: IOption[];
  defaultValues: IOption[];
  engineTypes: IOption[];
  supportInvisibleIndex?: boolean;
}

interface IDatabaseSupportFieldResponse {
  columnTypes?: IColumnTypes[];
  charsets?: ICharset[];
  collations?: ICollation[];
  indexTypes?: IIndexTypes[];
  defaultValues?: IDefaultValue[];
  engineTypes?: IEngineType[];
  supportInvisibleIndex?: boolean;
}

export function mapDatabaseSupportFieldOptions(res?: IDatabaseSupportFieldResponse): IDatabaseSupportFieldOptions {
  return {
    columnTypes:
      res?.columnTypes?.map((i) => ({
        ...i,
        value: i.typeName,
        label: i.typeName,
      })) || [],
    charsets:
      res?.charsets?.map((i) => ({
        value: i.charsetName,
        label: i.charsetName,
        defaultCollationName: i.defaultCollationName,
      })) || [],
    collations:
      res?.collations?.map((i) => ({
        value: i.collationName,
        label: i.collationName,
        charset: i.charset,
      })) || [],
    indexTypes:
      res?.indexTypes?.map((i) => ({
        value: i.typeName,
        label: i.typeName,
      })) || [],
    defaultValues:
      res?.defaultValues?.map((i) => ({
        value: i.defaultValue,
        label: i.defaultValue,
      })) || [],
    engineTypes:
      res?.engineTypes?.map((i) => ({
        value: i.name,
        label: i.name,
      })) || [],
    supportInvisibleIndex: res?.supportInvisibleIndex === true,
  };
}

export function buildBaseInfoFormValues(tableDetails: IEditTableInfo) {
  return {
    name: tableDetails.name,
    comment: tableDetails.comment,
    charset: tableDetails.charset,
    collation: tableDetails.collate,
    engine: tableDetails.engine,
    incrementValue: tableDetails.incrementValue,
  };
}

export function filterCollationsByCharset(collations: IOption[], charset?: string | null): IOption[] {
  if (!charset) {
    return collations;
  }
  return collations.filter((collation) => !collation.charset || collation.charset === charset);
}

export function isCharsetCollationCompatible(
  charset: string | null | undefined,
  collation: string | number | null | undefined,
  collations: IOption[],
): boolean {
  if (!charset || !collation) {
    return true;
  }
  const option = collations.find((item) => item.value === collation);
  return !option?.charset || option.charset === charset;
}

export function getCompatibleCollationValue(
  charset: string | null | undefined,
  collation: string | number | null | undefined,
  collations: IOption[],
): string | number | null | undefined {
  return isCharsetCollationCompatible(charset, collation, collations) ? collation : null;
}

export function validateTableCharsetCollations(
  table: Pick<IEditTableInfo, 'charset' | 'collate' | 'columnList'>,
  collations: IOption[],
): boolean {
  if (!isCharsetCollationCompatible(table.charset, table.collate, collations)) {
    return false;
  }
  return table.columnList.every((column: IColumnItemNew) =>
    isCharsetCollationCompatible(column.charSetName, column.collationName, collations),
  );
}

export function getFirstTablePreviewSql(result?: Array<{ sql?: string | null }>): string | null {
  const sql = result?.[0]?.sql?.trim();
  return sql || null;
}
