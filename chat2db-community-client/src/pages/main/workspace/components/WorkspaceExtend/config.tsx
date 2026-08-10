import i18n from '@/i18n';
import Output from '@/components/Output';
import GlobalExtendComponents from './GlobalExtendComponents';
import SaveList from '../SaveList';
import ViewDDL from '@/components/ViewDDL';
import TaskCenter from '@/blocks/ImportAndExport/components/TaskCenter';
import { canImportExport } from '@/utils/env';

interface IToolbar {
  code: string;
  title: string;
  icon: string;
  components: any;
}

export enum GlobalComponents {
  view_ddl = 'viewDDL',
  account_grants = 'accountGrants',
  executive_log = 'executiveLog',
  save_list = 'saveList',
  task_center = 'taskCenter',
}

export const globalComponents: {
  [key in GlobalComponents]?: any;
} = {
  [GlobalComponents.view_ddl]: ViewDDL,
  [GlobalComponents.executive_log]: Output,
  [GlobalComponents.save_list]: SaveList,
  [GlobalComponents.task_center]: TaskCenter,
};

export const extendConfig: IToolbar[] = [
  {
    code: 'info',
    title: i18n('common.title.info'),
    icon: 'icon-extend-nav-info',
    components: GlobalExtendComponents,
  },
  {
    code: 'executiveLog',
    title: i18n('common.title.executiveLogging'),
    icon: 'icon-clipboard',
    components: globalComponents.executiveLog,
  },
  {
    code: 'saveList',
    title: i18n('workspace.title.savedConsole'),
    icon: 'icon-clipboard-list',
    components: globalComponents.saveList,
  },
  ...(canImportExport
    ? [
        {
          code: GlobalComponents.task_center,
          title: i18n('workspace.title.exportProgressBar'),
          icon: 'icon-export-details',
          components: globalComponents.taskCenter,
        },
      ]
    : []),
];
