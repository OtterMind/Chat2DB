import { createWithEqualityFn } from 'zustand/traditional';
import { devtools } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';

import {
  ISchemaDiffResult,
  ITableDiff,
  ICompareOption,
  IMigrateResult,
} from '@/typings/schemaDiff';

export interface IConnectionOption {
  id: number;
  alias: string;
  dbType: string;
}

export interface ISchemaDiffStore {
  sourceDataSource: IConnectionOption | null;
  targetDataSource: IConnectionOption | null;
  sourceDatabase: string;
  targetDatabase: string;
  sourceSchema: string;
  targetSchema: string;
  compareOption: ICompareOption;
  compareResult: ISchemaDiffResult | null;
  selectedTableDiffs: Record<string, boolean>;
  selectedStatementIndexes: Record<number, boolean>;
  detailViewTableName: string | null;
  comparing: boolean;
  migrationExecuting: boolean;
  migrationResult: IMigrateResult | null;
}

const defaultCompareOption: ICompareOption = {
  compareColumn: true,
  compareIndex: true,
  compareForeignKey: true,
  compareTableOption: true,
  caseSensitive: false,
  excludeDeprecated: true,
  ignoreCharsetAlias: true,
  ignoreIntegerDisplayWidth: true,
  ignoreAutoIncrement: true,
};

export const initSchemaDiffStore = {
  sourceDataSource: null,
  targetDataSource: null,
  sourceDatabase: '',
  targetDatabase: '',
  sourceSchema: '',
  targetSchema: '',
  compareOption: defaultCompareOption,
  compareResult: null,
  selectedTableDiffs: {},
  selectedStatementIndexes: {},
  detailViewTableName: null,
  comparing: false,
  migrationExecuting: false,
  migrationResult: null,
};

export const useSchemaDiffStore = createWithEqualityFn<ISchemaDiffStore>(
  devtools(() => initSchemaDiffStore),
  shallow,
);

export const setSourceDataSource = (sourceDataSource: IConnectionOption | null) =>
  useSchemaDiffStore.setState({ sourceDataSource, sourceDatabase: '', sourceSchema: '', compareResult: null });

export const setTargetDataSource = (targetDataSource: IConnectionOption | null) =>
  useSchemaDiffStore.setState({ targetDataSource, targetDatabase: '', targetSchema: '', compareResult: null });

export const setSourceDatabase = (sourceDatabase: string) =>
  useSchemaDiffStore.setState({ sourceDatabase, sourceSchema: '', compareResult: null });

export const setTargetDatabase = (targetDatabase: string) =>
  useSchemaDiffStore.setState({ targetDatabase, targetSchema: '', compareResult: null });

export const setSourceSchema = (sourceSchema: string) =>
  useSchemaDiffStore.setState({ sourceSchema, compareResult: null });

export const setTargetSchema = (targetSchema: string) =>
  useSchemaDiffStore.setState({ targetSchema, compareResult: null });

export const setCompareOption = (compareOption: ICompareOption) =>
  useSchemaDiffStore.setState({ compareOption, compareResult: null });

export const setCompareResult = (compareResult: ISchemaDiffResult | null) =>
  useSchemaDiffStore.setState({ compareResult, selectedTableDiffs: {}, selectedStatementIndexes: {}, detailViewTableName: null });

export const setComparing = (comparing: boolean) =>
  useSchemaDiffStore.setState({ comparing });

export const setSelectedTableDiffs = (selectedTableDiffs: Record<string, boolean>) =>
  useSchemaDiffStore.setState({ selectedTableDiffs });

export const setSelectedStatementIndexes = (selectedStatementIndexes: Record<number, boolean>) =>
  useSchemaDiffStore.setState({ selectedStatementIndexes });

export const setDetailViewTableName = (detailViewTableName: string | null) =>
  useSchemaDiffStore.setState({ detailViewTableName });

export const setMigrationExecuting = (migrationExecuting: boolean) =>
  useSchemaDiffStore.setState({ migrationExecuting });

export const setMigrationResult = (migrationResult: IMigrateResult | null) =>
  useSchemaDiffStore.setState({ migrationResult });

export const resetStore = () =>
  useSchemaDiffStore.setState({ ...initSchemaDiffStore });
