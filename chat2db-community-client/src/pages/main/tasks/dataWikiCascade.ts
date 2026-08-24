export interface DataWikiResourceSelection {
  dataSourceId: number;
  dataSourceName?: string;
  databaseName?: string;
  schemaName?: string;
  tableName: string;
}

interface DataSourceIdentity {
  id: number;
  alias?: string;
}

export interface DataWikiCascadeSelectionOption {
  kind?: string;
  dataSourceId?: number;
  dataSourceName?: string;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
}

function segment(path: Array<string | number>, prefix: string) {
  const value = path.find((item) => String(item).startsWith(prefix));
  return value === undefined ? undefined : String(value).slice(prefix.length);
}

export function dataWikiSelectionsFromCascadeValue(
  value: unknown,
  dataSources: DataSourceIdentity[],
): DataWikiResourceSelection[] {
  if (!Array.isArray(value) || value.length === 0) return [];
  const paths = Array.isArray(value[0]) ? value : [value];
  return (paths as Array<Array<string | number>>)
    .map((path) => {
      const dataSourceId = Number(segment(path, 'datasource:'));
      const tableName = segment(path, 'table:');
      if (!Number.isFinite(dataSourceId) || !tableName) return undefined;
      return {
        dataSourceId,
        dataSourceName: dataSources.find((source) => String(source.id) === String(dataSourceId))?.alias,
        databaseName: segment(path, 'database:'),
        schemaName: segment(path, 'schema:'),
        tableName,
      };
    })
    .filter((item): item is DataWikiResourceSelection => Boolean(item));
}

export function dataWikiSelectionsFromCascadeOptions(
  selectedOptions: unknown,
  value: unknown,
  dataSources: DataSourceIdentity[],
): DataWikiResourceSelection[] {
  if (!Array.isArray(selectedOptions) || selectedOptions.length === 0) {
    return dataWikiSelectionsFromCascadeValue(value, dataSources);
  }
  const optionPaths = Array.isArray(selectedOptions[0]) ? selectedOptions : [selectedOptions];
  const selections = (optionPaths as DataWikiCascadeSelectionOption[][])
    .map((path) => {
      const table = [...path].reverse().find((option) => option.kind === 'TABLE' && option.tableName);
      if (!table || !Number.isFinite(Number(table.dataSourceId))) return undefined;
      return {
        dataSourceId: Number(table.dataSourceId),
        dataSourceName:
          table.dataSourceName ||
          dataSources.find((source) => String(source.id) === String(table.dataSourceId))?.alias,
        databaseName: table.databaseName,
        schemaName: table.schemaName,
        tableName: table.tableName as string,
      };
    })
    .filter((item): item is DataWikiResourceSelection => Boolean(item));
  return selections.length ? selections : dataWikiSelectionsFromCascadeValue(value, dataSources);
}
