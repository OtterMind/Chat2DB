import { LangType } from '@/constants/settings';
import common from './common';
import connection from './connection';
import menu from './menu';
import setting from './setting';
import workspace from './workspace';
import dashboard from './dashboard';
import chat from './chat';
import login from './login';
import editTable from './editTable';
import editTableData from './editTableData';
import sqlEditor from './sqlEditor';
import spaceSetting from './spaceSetting';
import monaco from './monaco';
import ai from './ai';
import stream from './stream';
import feedback from './feedback';
import notification from './notification';
import redis from './redis';
import plugin from './plugin';

export default {
  lang: LangType.ES_ES,
  ...common,
  ...setting,
  ...connection,
  ...workspace,
  ...menu,
  ...dashboard,
  ...chat,
  ...login,
  ...editTable,
  ...editTableData,
  ...sqlEditor,
  ...spaceSetting,
  ...monaco,
  ...ai,
  ...stream,
  ...feedback,
  ...notification,
  ...redis,
  ...plugin,
};
