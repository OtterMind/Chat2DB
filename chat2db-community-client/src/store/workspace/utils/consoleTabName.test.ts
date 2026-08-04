import type { IWorkspaceTab } from '@/typings';
import {
  applyWorkspaceTabBoundInfo,
  buildConsoleDefaultTabName,
  isConsoleTabNameCustomized,
} from './consoleTabName';

const assertEqual = (actual: unknown, expected: unknown, message: string) => {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, received ${String(actual)}`);
  }
};

assertEqual(
  buildConsoleDefaultTabName({ dataSourceName: 'local', databaseName: 'orders', schemaName: 'public' }),
  'orders[local]',
  'database-based default name',
);
assertEqual(
  buildConsoleDefaultTabName({ dataSourceName: 'local', schemaName: 'public' }),
  'public[local]',
  'schema-based default name',
);
assertEqual(
  isConsoleTabNameCustomized('orders[local]', {
    dataSourceName: 'local',
    databaseName: 'orders',
  }),
  false,
  'legacy default name is inferred as automatic',
);
assertEqual(
  isConsoleTabNameCustomized('monthly report', {
    dataSourceName: 'local',
    databaseName: 'orders',
  }),
  true,
  'legacy renamed tab is inferred as customized',
);
assertEqual(
  isConsoleTabNameCustomized('monthly report', {
    dataSourceName: 'local',
    databaseName: 'orders',
    nameCustomized: null,
  }),
  true,
  'nullable legacy marker still infers a customized name',
);
assertEqual(
  isConsoleTabNameCustomized('orders[local]', {
    dataSourceName: 'local',
    databaseName: 'orders',
    nameCustomized: null,
  }),
  false,
  'nullable legacy marker still infers an automatic name',
);

const automaticTab: IWorkspaceTab = {
  id: 1,
  type: 'console' as IWorkspaceTab['type'],
  title: 'orders[local]',
  uniqueData: {
    consoleId: 1,
    dataSourceId: 1,
    dataSourceName: 'local',
    databaseName: 'orders',
    nameCustomized: false,
  },
};
const reboundAutomaticTab = applyWorkspaceTabBoundInfo(automaticTab, {
  databaseName: 'analytics',
  schemaName: undefined,
});
assertEqual(reboundAutomaticTab.title, 'analytics[local]', 'automatic tab follows database selection');
assertEqual(reboundAutomaticTab.uniqueData?.nameCustomized, false, 'automatic state is retained');
const reboundDataSourceTab = applyWorkspaceTabBoundInfo(reboundAutomaticTab, {
  dataSourceName: 'warehouse',
});
assertEqual(reboundDataSourceTab.title, 'analytics[warehouse]', 'automatic tab follows data-source selection');

const customizedTab: IWorkspaceTab = {
  ...automaticTab,
  title: 'monthly report',
  uniqueData: {
    ...automaticTab.uniqueData,
    nameCustomized: true,
  },
};
const reboundCustomizedTab = applyWorkspaceTabBoundInfo(customizedTab, {
  dataSourceName: 'warehouse',
  databaseName: 'analytics',
});
assertEqual(reboundCustomizedTab.title, 'monthly report', 'customized tab name is retained');
assertEqual(reboundCustomizedTab.uniqueData?.nameCustomized, true, 'customized state is retained');

const explicitCustomizedDefaultName = isConsoleTabNameCustomized('orders[local]', {
  dataSourceName: 'local',
  databaseName: 'orders',
  nameCustomized: true,
});
assertEqual(explicitCustomizedDefaultName, true, 'explicit customization wins over legacy name inference');

console.log('Console tab name tests passed');
