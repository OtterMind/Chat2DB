import type { IBoundInfo, IWorkspaceTab } from '@/typings';

type ConsoleTabNameContext = Pick<IBoundInfo, 'dataSourceName' | 'databaseName' | 'schemaName'>;

export const buildConsoleDefaultTabName = ({
  dataSourceName,
  databaseName,
  schemaName,
}: ConsoleTabNameContext): string => {
  let name = databaseName || schemaName || '';
  if (dataSourceName) {
    name += `[${dataSourceName}]`;
  }
  return name;
};

export const isConsoleTabNameCustomized = (
  title: string,
  boundInfo: ConsoleTabNameContext & Pick<IBoundInfo, 'nameCustomized'>,
): boolean => {
  if (boundInfo.nameCustomized !== undefined) {
    return boundInfo.nameCustomized;
  }
  return title !== buildConsoleDefaultTabName(boundInfo);
};

export const applyWorkspaceTabBoundInfo = (tab: IWorkspaceTab, data: IBoundInfo): IWorkspaceTab => {
  const nextBoundInfo = {
    ...tab.uniqueData,
    ...data,
  };
  if (tab.type !== 'console') {
    return {
      ...tab,
      uniqueData: nextBoundInfo,
    };
  }

  const nameCustomized = isConsoleTabNameCustomized(tab.title, {
    ...tab.uniqueData,
    nameCustomized: data.nameCustomized ?? tab.uniqueData?.nameCustomized,
  });
  return {
    ...tab,
    title: nameCustomized ? tab.title : buildConsoleDefaultTabName(nextBoundInfo),
    uniqueData: {
      ...nextBoundInfo,
      nameCustomized,
    },
  };
};
