import i18n from '@/i18n';
import Output from '@/components/Output';
import GlobalExtendComponents from './GlobalExtendComponents';
import InnodbStatusPanel from '../InnodbStatusPanel';
import SaveList from '../SaveList';
import ViewDDL from '@/components/ViewDDL';
import TaskCenter from '@/blocks/ImportAndExport/components/TaskCenter';
import { canImportExport } from '@/utils/env';
import { Bookmark, Info, ListTodo, RotateCcwClock, type LucideIcon } from 'lucide-react';

export interface IToolbar {
  code: string;
  title: string;
  icon: string | LucideIcon;
  components: any;
}

export enum GlobalComponents {
  view_ddl = 'viewDDL',
  account_grants = 'accountGrants',
  innodb_status = 'innodbStatus',
  executive_log = 'executiveLog',
  save_list = 'saveList',
  task_center = 'taskCenter',
}

export const globalComponents: {
  [key in GlobalComponents]?: any;
} = {
  [GlobalComponents.view_ddl]: ViewDDL,
  [GlobalComponents.innodb_status]: InnodbStatusPanel,
  [GlobalComponents.executive_log]: Output,
  [GlobalComponents.save_list]: SaveList,
  [GlobalComponents.task_center]: TaskCenter,
};

export const standaloneExtendConfig: IToolbar[] = [
  {
    code: 'info',
    title: i18n('common.title.info'),
    icon: Info,
    components: GlobalExtendComponents,
  },
];

export const workspaceRecordEntryConfig: IToolbar = {
  code: GlobalComponents.executive_log,
  title: i18n('common.title.executiveLogging'),
  icon: RotateCcwClock,
  components: globalComponents[GlobalComponents.executive_log],
};

export const workspaceRecordConfig: IToolbar[] = [
  workspaceRecordEntryConfig,
  ...(canImportExport
    ? [
        {
          code: GlobalComponents.task_center,
          title: i18n('workspace.title.exportProgressBar'),
          icon: ListTodo,
          components: globalComponents[GlobalComponents.task_center],
        },
      ]
    : []),
  {
    code: GlobalComponents.save_list,
    title: i18n('workspace.title.savedConsole'),
    icon: Bookmark,
    components: globalComponents[GlobalComponents.save_list],
  },
];

export const extendConfig: IToolbar[] = [...standaloneExtendConfig, ...workspaceRecordConfig];

export function isWorkspaceRecordCode(code?: string | null) {
  return workspaceRecordConfig.some((item) => item.code === code);
}
