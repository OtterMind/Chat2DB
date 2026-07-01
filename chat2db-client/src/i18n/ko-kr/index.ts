import { LangType } from '@/constants'
import menu from './menu';
import common from './common';
import connection from './connection';
import setting from './setting';
import workspace from './workspace';
import dashboard from './dashboard';
import chat from './chat';
import team from './team'
import login from './login';
import editTable from './editTable';
import editTableData from './editTableData';
import sqlEditor from './sqlEditor'

export default {
  lang: LangType.KO_KR,
  ...connection,
  ...common,
  ...setting,
  ...workspace,
  ...menu,
  ...connection,
  ...dashboard,
  ...chat,
  ...team,
  ...login,
  ...editTable,
  ...editTableData,
  ...sqlEditor
};
